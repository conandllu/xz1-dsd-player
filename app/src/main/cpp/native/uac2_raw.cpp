// SPDX-License-Identifier: GPL-3.0-or-later
// uac2_raw.cpp — UAC2 描述符解析 / 接口独占（claim + detach kernel）/ 时钟控制
#include "uac2_raw.h"
#include "uac2_parse.h"

#include <android/log.h>
#include <libusb.h>

#include <cstring>

#define LOG_TAG "dsdplayer.uac2"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace uac2 {

namespace {

libusb_context* g_ctx = nullptr;
libusb_device_handle* g_handle = nullptr;   // 全局单设备（本机只有一个 DAC）

constexpr uint8_t kAudioClass = 0x01;
constexpr uint8_t kSubAC = 0x01;
constexpr uint8_t kSubAS = 0x02;
constexpr uint8_t kCsInterface = 0x24;
constexpr uint8_t kAcHeader = 0x01;
constexpr uint8_t kAcClockSource = 0x0A;
constexpr uint8_t kAcClockSelector = 0x0B;
constexpr uint8_t kAsGeneral = 0x01;
constexpr uint8_t kAsFormatTypeDesc = 0x02; // CS_AS: FORMAT_TYPE 描述符
constexpr uint8_t kAsFormatTypeI = 0x02;
constexpr uint8_t kAsFormatTypeDsd = 0x04; // bFormatType = GENERIC（DSD native）
constexpr uint8_t kUacV1SETCUR = 0x01;
constexpr uint8_t kUacV2CUR = 0x01;
constexpr uint8_t kUac2ClockFreq = 0x01;
constexpr uint8_t kUac2DsdFormat = 0x07;   // DSDFormat 控制选择器

int libusb_init_once(std::string* err) {
    if (g_ctx) return 0;
    // 用 init_context 显式传 NO_DEVICE_DISCOVERY：
    // libusb_set_option(nullptr, ...) 会隐式创建默认 context，从而去枚举 /dev/bus/usb
    // （真机日志里的 avc denied 就是它），而且对新建的 ctx 也不生效。
    libusb_init_option opts[1];
    opts[0].option = LIBUSB_OPTION_NO_DEVICE_DISCOVERY;
    opts[0].value.ival = 1;
    int r = libusb_init_context(&g_ctx, opts, 1);
    if (r != LIBUSB_SUCCESS) {
        if (err) *err = "libusb_init 失败";
        g_ctx = nullptr;
    }
    return r;
}

} // namespace

// ── 探测 ────────────────────────────────────────────────────────────────

bool probe(int fd, int device_address, StreamInfo* info, std::string* err) {
    if (libusb_init_once(err) != 0) return false;

    if (g_handle) { // 上次的句柄未关（异常路径兜底）
        libusb_close(g_handle);
        g_handle = nullptr;
    }
    int r = libusb_wrap_sys_device(g_ctx, (intptr_t)fd, &g_handle);
    if (r != LIBUSB_SUCCESS) {
        if (err) *err = "libusb_wrap_sys_device 失败（USB 权限或 fd 已失效）";
        g_handle = nullptr;
        return false;
    }

    libusb_device* dev = libusb_get_device(g_handle);
    libusb_config_descriptor* cfg = nullptr;
    r = libusb_get_active_config_descriptor(dev, &cfg);
    if (r != LIBUSB_SUCCESS) {
        if (err) *err = "无法读取活动配置描述符";
        return false;
    }

    StreamInfo out;
    int best_score = -1;
    for (uint8_t i = 0; i < cfg->bNumInterfaces; ++i) {
        const libusb_interface& intf = cfg->interface[i];
        for (int a = 0; a < intf.num_altsetting; ++a) {
            const libusb_interface_descriptor& alt = intf.altsetting[a];
            if (alt.bInterfaceClass != kAudioClass) continue;

            if (alt.bInterfaceSubClass == kSubAC) {
                if (out.ac_if < 0) out.ac_if = alt.bInterfaceNumber;
                // AC 实体：ClockSource / ClockSelector
                const uint8_t* p = alt.extra;
                const uint8_t* end = p + alt.extra_length;
                while (p + 3 <= end && p[0] >= 3 && p + p[0] <= end) {
                    if (p[1] == kCsInterface && p[2] == kAcClockSource && p[0] >= 8 &&
                        out.clock_id == 0) {
                        out.clock_id = p[3];
                        // ClockSource: [0]=bLength [1]=0x24 [2]=0x0A [3]=bClockID
                        //               [4]=bmAttributes [5]=bmControls [6]=bAssocTerminal [7]=iClockSource
                        out.clock_freq_ctl = p[5] & 0x03;   // 3 = 可读可写
                    }
                    if (p[1] == kCsInterface && p[2] == kAcClockSelector && p[0] >= 6) {
                        out.clock_selector_id = p[3];
                    }
                    p += p[0];
                }
                continue;
            }
            if (alt.bInterfaceSubClass != kSubAS) continue;
            if ((alt.bAlternateSetting) == 0) {
                // alt0 无端点，跳过但仍记录接口号
            }

            // 端点：等时 OUT
            uint8_t ep = 0;
            uint16_t mp = 0;
            int mult = 1;
            for (uint8_t e = 0; e < alt.bNumEndpoints; ++e) {
                const libusb_endpoint_descriptor& epd = alt.endpoint[e];
                bool iso = (epd.bmAttributes & 0x03) == 0x01;
                bool outdir = (epd.bEndpointAddress & 0x80) == 0;
                if (iso && outdir) {
                    ep = epd.bEndpointAddress;
                    mp = epd.wMaxPacketSize & 0x07FF;
                    mult = ((epd.wMaxPacketSize >> 11) & 0x03) + 1;
                    break;
                }
            }
            if (ep == 0) continue;

            // 解析 CS AS 描述符（统一走 uac2_parse.h，主机侧有对应的单测）
            uac2parse::AltParsed parsed =
                uac2parse::parse_alt_extra(alt.extra, alt.extra_length);
            int fmt_type = parsed.fmt_type;
            int subslot = parsed.subslot;
            int bitres = parsed.bitres;
            int channels = parsed.channels;
            const std::vector<int>& rates = parsed.rates;
            (void)rates;

            int score = 0;
            if (fmt_type == 1 && bitres >= 24) score += 10;
            if (fmt_type == 1 && bitres >= 16) score += 5;
            if (fmt_type == 4) score += 100; // DSD native 优先（若走 native 模式）
            if (channels >= 2) score += 2;

            if (alt.bAlternateSetting > 0) {
                int if_num = alt.bInterfaceNumber;
                // 合并到同接口的 info：
                StreamInfo* tgt = nullptr;
                if (if_num == out.as_if) tgt = &out; // 简化：只维护最优接口
                if (!tgt) {
                    tgt = &out;
                    // 若已记录其他接口且分数更低，则整体替换
                }
                AltInfo ai{alt.bAlternateSetting, fmt_type, subslot, bitres,
                           0 /* dsd_rate 由 CC 取得，这里不依赖 */,
                           channels > 0 ? channels : 2, (int)alt.bInterfaceNumber, rates};
                if (fmt_type == 4) {
                    // UAC2 DSD GENERIC：subslot=1 字节，bitres 表示每帧 DSD bit
                    tgt->caps.dsd_alts.push_back(ai);
                } else {
                    if (bitres >= 24 || subslot >= 3) tgt->caps.pcm24 = true;
                    tgt->caps.pcm_subslot = subslot > 0 ? subslot : 3;
                    tgt->caps.pcm_alts.push_back(ai);       // ★ 保留每个 alt 的速率表
                    for (int rr : rates) {
                        if (std::find(tgt->caps.pcm_dsd_rates.begin(),
                                      tgt->caps.pcm_dsd_rates.end(), rr) ==
                            tgt->caps.pcm_dsd_rates.end())
                            tgt->caps.pcm_dsd_rates.push_back(rr);
                    }
                }
                // 记录该接口的端点/包长（以最佳 alt 为准）
                if (score >= best_score) {
                    best_score = score;
                    tgt->as_if = if_num;
                    tgt->ep_out = ep;
                    tgt->max_packet = mp;
                    tgt->iso_mult = mult;
                    tgt->subslot = subslot > 0 ? subslot : 4;
                    tgt->channels = ai.channels;
                    if (fmt_type == 4) tgt->dsd_alt = alt.bAlternateSetting;
                    else tgt->pcm_alt = alt.bAlternateSetting;
                }
            }
        }
    }
    libusb_free_config_descriptor(cfg);

    // DSD native 接口：若存在多个 dsd alt，把第一个 alt 的 GENERIC 描述符里
    // bDataBitResolution 当作位率索引；实际位率通过 SET_CUR freq = dsdHz 设置。
    if (out.as_if < 0 || out.ep_out == 0) {
        if (err) *err = "未找到 AudioStreaming（播放）等时端点";
        return false;
    }
    *info = out;
    LOGI("probe: %s", info->describe().c_str());
    return true;
}

int pick_alt(const StreamInfo& info, int rate_hz, bool want_dsd) {
    const std::vector<AltInfo>& pool = want_dsd ? info.caps.dsd_alts : info.caps.pcm_alts;
    if (pool.empty()) return want_dsd ? info.dsd_alt : info.pcm_alt;
    const AltInfo* exact = nullptr;
    const AltInfo* widest = nullptr;
    for (const AltInfo& a : pool) {
        if (a.if_num >= 0 && info.as_if >= 0 && a.if_num != info.as_if) continue;  // 别的接口的 alt 号不能用
        bool supports = a.rates.empty() ||
            std::find(a.rates.begin(), a.rates.end(), rate_hz) != a.rates.end();
        if (supports && (!exact || a.subslot_size > exact->subslot_size)) exact = &a;
        if (!widest || a.subslot_size > widest->subslot_size) widest = &a;
    }
    if (exact) {
        LOGI("pick_alt: rate=%d → alt=%d (subslot=%d bitres=%d)",
             rate_hz, exact->alt, exact->subslot_size, exact->bit_res);
        return exact->alt;
    }
    LOGW("pick_alt: 没有 alt 声明支持 %dHz，退回 subslot 最大的 alt=%d（可能不出声）",
         rate_hz, widest ? widest->alt : -1);
    return widest ? widest->alt : (want_dsd ? info.dsd_alt : info.pcm_alt);
}

/** 每个 PCM alt 的「位宽: 最低-最高速率」摘要 */
static std::string alt_table_impl(const StreamInfo& info) {
    std::string s;
    for (const AltInfo& a : info.caps.pcm_alts) {
        int lo = 0, hi = 0;
        for (int r : a.rates) {
            if (!lo || r < lo) lo = r;
            if (r > hi) hi = r;
        }
        s += "\n  alt " + std::to_string(a.alt) + ": " + std::to_string(a.bit_res) + "bit/" +
             std::to_string(a.subslot_size) + "B ";
        if (a.rates.empty()) s += "rates=?";
        else s += std::to_string(lo) + "-" + std::to_string(hi) + " (" +
                  std::to_string(a.rates.size()) + ")";
    }
    for (const AltInfo& a : info.caps.dsd_alts) {
        s += "\n  dsd alt " + std::to_string(a.alt) + ": bitres " + std::to_string(a.bit_res);
    }
    return s;
}

std::string StreamInfo::describe() const {
    std::string s = "AC if" + std::to_string(ac_if) +
                    ", AS if" + std::to_string(as_if) +
                    ", clk " + std::to_string(clock_id) +
                    (clock_selector_id ? "/sel " + std::to_string(clock_selector_id) : "") +
                    ", ep 0x" + [&] {
                        static const char* hx = "0123456789abcdef";
                        std::string h{hx[(ep_out >> 4) & 0xF], hx[ep_out & 0xF]};
                        return h;
                    }() +
                    ", maxpkt " + std::to_string(max_packet) + " x" +
                    std::to_string(iso_mult) +
                    (clock_freq_ctl ? " (freq ctl:" + std::to_string(clock_freq_ctl) + ")" : " (no freq ctl)") +
                    alt_table_impl(*this) +
                    ", pcm_alt " + std::to_string(pcm_alt) +
                    ", dsd_alt " + std::to_string(dsd_alt) +
                    (caps.pcm24 ? " [24bit PCM]" : " [PCM?]");
    return s;
}

// ── 流打开 / 关闭 ────────────────────────────────────────────────────────

// 打开/重开设备句柄。close_stream() 会把 g_handle 置空，但 Java 侧 fd 依然有效
// （libusb 对 wrap_sys_device 的 fd 设了 fd_keep=1，libusb_close 不会 close 它），
// 所以这里可以安全地重复 wrap。
static bool ensure_open(int fd, std::string* err) {
    if (g_handle) return true;
    if (fd < 0) {
        if (err) *err = "无效的设备 fd";
        return false;
    }
    if (libusb_init_once(err) != 0) return false;
    int r = libusb_wrap_sys_device(g_ctx, (intptr_t)fd, &g_handle);
    if (r != LIBUSB_SUCCESS) {
        g_handle = nullptr;
        if (err) {
            *err = std::string("重新打开设备失败: ") + libusb_error_name(r) +
                   "（USB 权限被回收或 fd 失效）";
        }
        return false;
    }
    LOGI("ensure_open: 重新 wrap fd=%d 成功", fd);
    return true;
}

static bool claim(int fd, int if_num, const char* what, std::string* err) {
    // detach 内核 snd-usb-audio —— 这就是"独占"
    libusb_kernel_driver_active(g_handle, if_num);
    int rc = libusb_detach_kernel_driver(g_handle, if_num);
    if (rc != 0 && rc != LIBUSB_ERROR_NOT_FOUND && rc != LIBUSB_ERROR_NOT_SUPPORTED) {
        LOGW("detach %s if%d: %s", what, if_num, libusb_error_name(rc));
    }
    rc = libusb_claim_interface(g_handle, if_num);
    if (rc != LIBUSB_SUCCESS) {
        if (err) *err = std::string("claim_interface(") + what +
                        " if" + std::to_string(if_num) + ") " + libusb_error_name(rc) +
                        "，可能被系统音频占用";
        return false;
    }
    return true;
}

bool open_stream(int fd, int device_address, const StreamInfo& info, int alt,
                 int rate_hz, bool dsd, std::string* err) {
    std::string e1;
    if (!ensure_open(fd, &e1)) {
        if (err) *err = e1;
        return false;
    }
    // AC 接口（时钟控制）claim 失败不致命
    claim(fd, info.ac_if, "AC", nullptr);
    // AS 接口：失败也继续 —— Java 侧 UsbDeviceConnection.claimInterface(force=true)
    // 可能已经把它 claim 下来了（同一个 fd，claim 状态共享），此时这里会返回 BUSY。
    std::string ce;
    if (!claim(fd, info.as_if, "AS", &ce)) {
        LOGW("AS claim 未成功（%s），继续尝试切 alt —— 若 Java 侧已 force claim 则正常", ce.c_str());
    }

    // 先设时钟再切 alt（XMOS 等固件在 alt 切换瞬间锁时钟）
    if (!set_rate(fd, device_address, info, rate_hz, dsd, err)) {
        if (dsd) {
            // DSD 格式控制被 stall：这台 DAC 不接受 native DSD，直接失败让上层回退 DoP
            LOGW("open_stream: DSD 模式不可用（%s），交由上层回退 DoP",
                 err ? err->c_str() : "?");
            libusb_release_interface(g_handle, info.as_if);
            return false;
        }
        // PCM：部分设备 alt0 时 SET_CUR 失败，切 alt 后再试
    }
    int rc = libusb_set_interface_alt_setting(g_handle, info.as_if, alt);
    (void)0;
    if (rc != LIBUSB_SUCCESS) {
        if (err) *err = "set_alt(" + std::to_string(alt) + ") 失败";
        libusb_release_interface(g_handle, info.as_if);
        return false;
    }
    if (!set_rate(fd, device_address, info, rate_hz, dsd, err)) {
        if (dsd) {
            LOGW("二次 SET_CUR: DSD 仍不可用，回退 DoP");
            libusb_set_interface_alt_setting(g_handle, info.as_if, 0);
            libusb_release_interface(g_handle, info.as_if);
            return false;
        }
        LOGW("二次 SET_CUR %dHz 仍失败", rate_hz);
    }
    // DAC 可能"照抄回显"却根本不支持某个档位（如 32k），提前提示
    static const int kKnownRates[] = {44100, 48000, 88200, 96000, 176400, 192000,
                                      352800, 384000, 705600, 768000, 2822400, 5644800,
                                      11289600, 22579200};
    bool known = false;
    for (int r : kKnownRates) if (r == rate_hz) { known = true; break; }
    if (!known) {
        LOGW("open_stream: %d Hz 不在常见 UAC2 采样率列表内，DAC 可能实际不支持"
             "（SET_CUR 仍可能返回成功并回显）", rate_hz);
    }
    long now = get_rate(fd, device_address, info);
    LOGI("open_stream: 请求 %dHz，DAC 读回 %ldHz（alt=%d ep=0x%02x）", rate_hz, now, alt, info.ep_out);
    return true;
}

/** 读回当前采样率（UAC2 GET_CUR SAMPLING_FREQ_CONTROL） */
static long read_rate(int entity_id, int ac_if) {
    if (!g_handle) return -1;
    uint8_t cur[4] = {0, 0, 0, 0};
    uint16_t wIndex = (uint16_t(entity_id) << 8) | (uint8_t)ac_if;
    int r = libusb_control_transfer(g_handle, 0xA1, kUacV2CUR,
                                    kUac2ClockFreq << 8, wIndex, cur, 4, 500);
    if (r < 0) return -1;
    return (long)cur[0] | ((long)cur[1] << 8) | ((long)cur[2] << 16) | ((long)cur[3] << 24);
}

/** 在指定实体上 SET_CUR 采样率，返回 libusb 结果 */
static int write_rate(int entity_id, int ac_if, int rate_hz) {
    uint8_t payload[4] = {
        (uint8_t)(rate_hz & 0xFF), (uint8_t)((rate_hz >> 8) & 0xFF),
        (uint8_t)((rate_hz >> 16) & 0xFF), (uint8_t)((rate_hz >> 24) & 0xFF)};
    uint16_t wIndex = (uint16_t(entity_id) << 8) | (uint8_t)ac_if;
    return libusb_control_transfer(g_handle, 0x21, kUacV2CUR,
                                   kUac2ClockFreq << 8, wIndex, payload, 4, 500);
}

bool set_rate(int fd, int device_address, const StreamInfo& info, int rate_hz,
              bool dsd, std::string* err) {
    if (!ensure_open(fd, err)) return false;

    // 依次尝试 ClockSource（标准做法）→ ClockSelector（部分固件只认这个）
    int candidates[2] = {info.clock_id, info.clock_selector_id};
    bool ok = false;
    long readback = -1;
    for (int i = 0; i < 2 && !ok; ++i) {
        int ent = candidates[i];
        if (ent <= 0) continue;
        int rc = write_rate(ent, info.ac_if, rate_hz);
        readback = read_rate(ent, info.ac_if);
        LOGI("set_rate: ent=%d (if%d) val=%dHz data=%02x %02x %02x %02x rc=%s readback=%ld",
             ent, info.ac_if, rate_hz,
             rate_hz & 0xFF, (rate_hz >> 8) & 0xFF, (rate_hz >> 16) & 0xFF, (rate_hz >> 24) & 0xFF,
             rc < 0 ? libusb_error_name(rc) : "OK", readback);
        if (rc >= 0 && (readback == rate_hz || readback < 0)) {
            ok = true;
            break;
        }
        LOGW("set_rate: ent=%d 未生效（readback=%ld, 期望 %d）", ent, readback, rate_hz);
    }

    if (dsd) {
        // UAC2 DSD：AS 接口上 SET_CUR DSDFormat = 0（native DSD 直通）。
        // 各家用法不统一，被 stall 很常见 → 直接判失败，让上层回退 DoP（DoP 一定能出声）
        uint8_t dsd_fmt = 0;
        int rd = libusb_control_transfer(g_handle, 0x21, kUacV2CUR,
                                         kUac2DsdFormat << 8, (uint16_t)info.as_if,
                                         &dsd_fmt, 1, 500);
        LOGI("set_rate: DSDFormat SET_CUR ent=%d(if%d) wValue=0x%02x00 wLen=1 rc=%s",
             info.as_if, info.as_if, kUac2DsdFormat,
             rd < 0 ? libusb_error_name(rd) : "OK");
        if (rd < 0) {
            if (err) *err = std::string("DSD 格式控制不被支持（") + libusb_error_name(rd) +
                            "）→ 回退 DoP";
            return false;
        }
    }

    if (!ok) {
        // UAC1 兜底（少数老设备）
        uint8_t v1[2] = {(uint8_t)(rate_hz & 0xFF), (uint8_t)((rate_hz >> 8) & 0xFF)};
        int r2 = libusb_control_transfer(g_handle, 0x21, kUacV1SETCUR, 0x0100,
                                         (uint16_t)info.as_if, v1, 2, 500);
        LOGW("set_rate: UAC1 兜底 rc=%s", r2 < 0 ? libusb_error_name(r2) : "OK");
        if (r2 < 0) {
            if (err) *err = "SET_CUR 采样率失败（ClockSource/Selector 均未生效）";
            return false;
        }
    }
    return true;
}

void close_stream(int fd, const StreamInfo& info) {
    if (!g_handle) return;
    (void)fd;
    if (info.as_if >= 0) {
        libusb_set_interface_alt_setting(g_handle, info.as_if, 0);
        libusb_release_interface(g_handle, info.as_if);
        libusb_attach_kernel_driver(g_handle, info.as_if);
    }
    if (info.ac_if >= 0) {
        libusb_release_interface(g_handle, info.ac_if);
        libusb_attach_kernel_driver(g_handle, info.ac_if);
    }
    libusb_close(g_handle);
    g_handle = nullptr;
    LOGI("close_stream: 已归还内核驱动");
}

long get_rate(int fd, int device_address, const StreamInfo& info) {
    if (!ensure_open(fd, nullptr)) return -1;
    long v = read_rate(info.clock_id, info.ac_if);
    if (v < 0 && info.clock_selector_id > 0) v = read_rate(info.clock_selector_id, info.ac_if);
    return v;
}

} // namespace uac2
