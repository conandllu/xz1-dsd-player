// SPDX-License-Identifier: GPL-3.0-or-later
// usb_stream.cpp — ISO 输出泵（实现见 usb_stream.h）
#include "usb_stream.h"
#include "uac2_raw.h"

#include <android/log.h>
#include <libusb.h>

#include <atomic>
#include <chrono>
#include <cstring>
#include <thread>
#include <vector>

#define LOG_TAG "dsdplayer.usb"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace usbaudio {

namespace {

constexpr int kXfers = 6;            // 在途传输数
constexpr int kPktsPerXfer = 8;      // 每个传输的微帧包数（8 × 125µs = 1ms）
// 8MB 环形缓冲：96k/24bit（768KB/s）≈10.4s、44.1k/16bit ≈23.8s。
// 真机第一百二十三轮：到服务器的链路有 10% 丢包 + 秒级停顿，4MB（≈5.5s）会被瞬间抽干 → URB 短包 → 「嗞」。
// 演进：1MB 时 96k 有 ~270 次/s 欠载 → 2MB 降到 ~10 次/s（供数周期性突发，余量仍薄）
//       → 4MB 让"每轮突发之间"的抖动有更大冗余。注意 Producer.kt 的 drainRing 预算同步。
constexpr int kRingBytes = 8 << 20;

// ── 无锁 SPSC 环形缓冲 ──
std::vector<uint8_t> ring(kRingBytes);
std::atomic<long> head{0}; // 写位置
std::atomic<long> tail{0}; // 读位置

StreamInfo g_info;
uint8_t g_ep = 0;
int g_maxpkt = 0;
int g_frame_bytes = 8;
bool g_dsd = false;
int g_rate = 44100;
std::atomic<bool> g_running{false};
long long g_bytes_per_sec = 0;
long long g_frames_per_sec = 0;    // USB 帧率（PCM=采样率；DSD=位率/32；DoP=载波率）
std::atomic<int> g_inflight{0};
std::thread g_evt;

std::atomic<long> g_bytes{0};
// 每微帧「帧数」的分数累加器（单位：帧 × 8000）
// 关键：这只 DAC 要求每包是整帧。按字节累加(44/45)仍是 5.5 帧/包 → 会被截成 5 帧 = 40000Hz。
std::atomic<long long> g_acc{0};
std::atomic<bool> g_mute{false};   // 暂停状态（语义标记，不再直接决定是否读缓冲）
std::atomic<bool> g_silent{false}; // 增益已到 0：从下一包起整包填静音、不读环形缓冲
std::atomic<bool> g_pending_discard{false};   // seek 后待丢弃旧缓冲
std::atomic<float> g_gain{1.0f};   // PCM 当前增益
std::atomic<float> g_target{1.0f}; // PCM 目标增益（暂停=0，恢复=1）
bool g_dop = false;                // DoP 模式（载荷是 DSD，不能乘增益）
bool g_dop_phase = false;          // DoP 静音帧的 marker 交替相位
std::atomic<long> g_submitted{0};
std::atomic<int> g_underruns{0};
// 起播阶段（还没拿到第一条真实数据）的欠载单独计数：稳态 0 但有起播欠载时，
// 用户听到的是"播放/切歌开头咔一声"，日志里别把它和稳态混在一起。
std::atomic<int> g_startup_underruns{0};
std::atomic<bool> g_first_data{false};
std::atomic<int> g_errors{0};

// ── USB 异步反馈（UAC2 explicit feedback）──────────────────────────────
// XD05 Bal 这类 async DAC 会在 IN 端点上每 bRefresh 个微帧回报一次"它实际需要多少
// 采样/微帧"（16.16 定点）。主机若按标称值恒定发送，DAC 内部 FIFO 会随晶振差
// （几十 ppm）缓慢漂移，漂到边界就漏/溢一次 —— 主机侧无日志，听感就是偶发「嗞」。
// 这里读回反馈值，按"每秒帧数 = fb × 8000 / 65536"换算，并限制在标称值的 ±2% 内。
uint8_t g_ep_fb = 0;
int g_fb_interval = 1;
libusb_transfer* g_fb_xfer = nullptr;
uint8_t g_fb_buf[4] = {0, 0, 0, 0};
std::atomic<bool> g_fb_valid{false};      // 收到过有效反馈
std::atomic<long long> g_fps_eff{0};      // 由反馈换算出的"每秒帧数"（0 = 用标称值）
std::atomic<long long> g_fb_last_log_ms{0};

std::vector<libusb_transfer*> g_xfers;
std::vector<std::vector<uint8_t>> g_bufs;
// 每个 xfer 的包长数组
std::vector<std::vector<int>> g_lents;

// 与 uac2_raw 共享 libusb context：libusb_init 已在 probe 时完成（NO_DEVICE_DISCOVERY）
// 这里直接用 Java 传入的 fd 再 wrap 一个 handle。
libusb_context* g_ctx = nullptr;
libusb_device_handle* g_handle = nullptr;

/**
 * 计算本 URB 各包长度（推进帧累加器一次），返回总字节数。
 * ★ 必须**先**算包长再读数据：交替速率(44.1k/88.2k/DoP)下每包总长会变，
 *   若按上一轮的旧长度读数据、却按新长度提交，边界处会带出旧字节 → 嗞嗞声。
 */
static int compute_lengths(libusb_transfer* x) {
    long long acc = g_acc.load(std::memory_order_relaxed);
    // 有反馈就用反馈换算出的帧率（已 clamp 到 ±2%），没有就用标称值
    long long fps = g_fps_eff.load(std::memory_order_relaxed);
    if (fps <= 0) fps = g_frames_per_sec;
    int total = 0;
    for (int i = 0; i < kPktsPerXfer; ++i) {
        acc += fps;
        long frames = acc / 8000;
        acc %= 8000;
        if (frames < 0) frames = 0;
        long len = frames * g_frame_bytes;
        if (len > g_maxpkt) {
            frames = g_maxpkt / (g_frame_bytes ? g_frame_bytes : 8);
            len = frames * g_frame_bytes;
        }
        x->iso_packet_desc[i].length = (int)len;
        total += (int)len;
    }
    g_acc.store(acc, std::memory_order_relaxed);
    return total;
}

/**
 * 填静音：
 *   DoP → 保持 DoP 帧结构（marker 交替）但载荷用 DSD 静音码 0x69，
 *         否则送纯 0 会让 DAC 丢掉 DoP 锁定；
 *   DSD native → 0x69；PCM → 0x00。
 */
static void fill_silence(uint8_t* buf, int len) {
    if (g_dop) {
        int i = 0;
        while (i + 8 <= len) {
            uint8_t ml = g_dop_phase ? 0xFA : 0x05;
            uint8_t mr = g_dop_phase ? 0x05 : 0xFA;
            g_dop_phase = !g_dop_phase;
            buf[i]     = 0x00; buf[i + 1] = 0x69; buf[i + 2] = 0x69; buf[i + 3] = ml;
            buf[i + 4] = 0x00; buf[i + 5] = 0x69; buf[i + 6] = 0x69; buf[i + 7] = mr;
            i += 8;
        }
        if (i < len) memset(buf + i, 0x00, len - i);
        return;
    }
    memset(buf, g_dsd ? 0x69 : 0x00, len);
}

void pump(libusb_transfer* xfer) {
    // 先算包长 → 总长，再按总长读数据
    int total = compute_lengths(xfer);
    xfer->length = total;
    int got = 0;
    // 已完全静音才跳过读缓冲；淡出期间必须继续取数据，否则波形被硬切 → 爆音
    const bool silent = g_silent.load(std::memory_order_relaxed);
    if (!silent) {
        long h = tail.load(std::memory_order_relaxed);
        long avail = head.load(std::memory_order_acquire) - h;
        if (avail > 0) {
            int n = (int)std::min<long>(avail, total);
            size_t off = (size_t)(h & (kRingBytes - 1));
            size_t first = std::min<size_t>(n, kRingBytes - off);
            memcpy(xfer->buffer, &ring[off], first);
            if (n > first) memcpy(xfer->buffer + first, &ring[0], n - first);
            tail.store(h + n, std::memory_order_release);
            got = n;
        }
    }
    if (got > 0 && !g_first_data.load(std::memory_order_relaxed)) {
        g_first_data.store(true, std::memory_order_relaxed);
    }
    if (silent) {
        // 全静音态：整段填静音（DoP 保留 marker），不消耗环形缓冲
        fill_silence(xfer->buffer, total);
    } else if (got < total) {
        // 只有缺得明显（≥2 帧）才算一次欠载；单包差几个字节属瞬态，可闻性无意义
        if (total - got >= g_frame_bytes * 2) {
            if (!g_first_data.load(std::memory_order_relaxed)) {
                g_startup_underruns.fetch_add(1, std::memory_order_relaxed);
            } else {
                g_underruns.fetch_add(1, std::memory_order_relaxed);
            }
#if LOG_NDEBUG == 0
            // 调试：每秒最多打一条，说明"差多少字节、环里当时还剩多少"
            static std::atomic<long> last_log_ms{0};
            long now_ms = (long)std::chrono::duration_cast<std::chrono::milliseconds>(
                    std::chrono::steady_clock::now().time_since_epoch()).count();
            long prev = last_log_ms.load(std::memory_order_relaxed);
            if (now_ms - prev >= 1000 && last_log_ms.compare_exchange_strong(prev, now_ms)) {
                long h2 = tail.load(std::memory_order_relaxed);
                long avail2 = head.load(std::memory_order_acquire) - h2;
                LOGW("欠载：URB 需 %d B，只取到 %d B（差 %d B），环里剩 %ld B",
                     total, got, total - got, avail2);
            }
#endif
        }
        fill_silence(xfer->buffer + got, total - got);
    }

    // PCM 增益平滑（10ms 斜坡）：暂停/恢复不再硬切，避免"啪"声
    // DoP / DSD native 的载荷是 DSD 位流，乘 gain 会破坏数据 → 这两条路径仍用静音码硬切
    if (!g_dsd && !g_dop && !silent) {
        float g0 = g_gain.load(std::memory_order_relaxed);
        float target = g_target.load(std::memory_order_relaxed);
        int samples = total / 4;
        if (g0 != target && samples > 0) {
            // 10 ms 斜坡，URB 内逐样本线性插值：任意边界都不出现台阶
            float step = g_frames_per_sec > 0
                ? (float)samples / (0.010f * (float)g_frames_per_sec) : 1.0f;
            float g1 = (g0 < target) ? std::min(target, g0 + step)
                                     : std::max(target, g0 - step);
            uint8_t* b = xfer->buffer;
            float d = (g1 - g0) / (float)(samples > 1 ? samples - 1 : 1);
            float g = g0;
            for (int i = 0; i < samples; ++i, g += d) {
                int32_t s;
                memcpy(&s, b + i * 4, 4);
                s = (int32_t)((float)s * g);
                memcpy(b + i * 4, &s, 4);
            }
            g_gain.store(g1, std::memory_order_relaxed);
            if (g1 <= 0.0f) {
                g_silent.store(true);      // 到 0 了：下一包起整包静音 + 停读缓冲
                LOGI("泵 淡出完成 gain=0.000（本包 %d 样本）", samples);
            } else if (g1 >= 1.0f) {
                LOGI("泵 淡入完成 gain=1.000（本包 %d 样本）", samples);
            }
        } else if (g0 <= 0.0f && target <= 0.0f) {
            g_silent.store(true);
            memset(xfer->buffer, 0x00, total);
        }
    }
    // seek 冲刷：等淡出真正结束（或 DSD 直切）后丢弃旧数据，再淡入。
    // ★ 必须尊重 g_mute：如果这次"安静"是**暂停**造成的（seek 还没走完就被暂停），
    //   这里若无条件淡入，就会出现"暂停后 gain 回到 1.000、声音继续放"并且丢掉缓冲
    //   —— 真机日志里那条 `seek：已丢弃旧缓冲，开始淡入` 夹在暂停中间就是这么来的。
    if (g_pending_discard.load(std::memory_order_relaxed)) {
        if (g_silent.load(std::memory_order_relaxed) || g_dsd || g_dop) {
            ring_discard();
            g_pending_discard.store(false);
            // 丢弃后**一律先保持静音**：此刻环形缓冲是空的，如果这里直接淡入，
            // 蒸出的 10 ms 全在"无数据"上，等新数据到达时 gain 已经 1.0（等于硬切）。
            // 由调用方在预填完成后显式 set_mute(false) 触发淡入；
            // 暂停中（g_mute=true）则等用户恢复，同样从 0 淡入。
            g_silent.store(true);
            g_target.store(0.0f);
            LOGI("泵 seek：已丢弃旧缓冲（保持静音，等预填完成后淡入）");
        }
    }
    int rc = libusb_submit_transfer(xfer);
    if (rc != LIBUSB_SUCCESS) {
        g_errors.fetch_add(1, std::memory_order_relaxed);
        g_inflight.fetch_sub(1);
        return;
    }
    g_submitted.fetch_add(1, std::memory_order_relaxed);
    g_bytes.fetch_add(total, std::memory_order_relaxed);
}

void on_done(libusb_transfer* xfer) {
    if (!g_running || xfer->status == LIBUSB_TRANSFER_CANCELLED ||
        xfer->status == LIBUSB_TRANSFER_NO_DEVICE) {
        g_inflight.fetch_sub(1);
        return;
    }
    if (xfer->status != LIBUSB_TRANSFER_COMPLETED) {
        g_errors.fetch_add(1, std::memory_order_relaxed);
    }
    pump(xfer);
}

} // namespace

/**
 * 反馈端点回调：解码 16.16 定点（每微帧样本数），换算成"每秒帧数"供 compute_lengths 用。
 * 数值异常（0 或离谱）时回落到标称值；全部限制在标称 ±2%，避免反馈坏掉就把声音搞坏。
 */
static void on_feedback(libusb_transfer* t) {
    if (t->status == LIBUSB_TRANSFER_COMPLETED && t->actual_length >= 4) {
        uint32_t raw = (uint32_t)g_fb_buf[0] | ((uint32_t)g_fb_buf[1] << 8) |
                       ((uint32_t)g_fb_buf[2] << 16) | ((uint32_t)g_fb_buf[3] << 24);
        // 16.16：整数部分 = 每微帧样本数
        long long fps = (long long)((double)raw * 8000.0 / 65536.0 + 0.5);
        long long nominal = g_frames_per_sec;
        if (fps > 0 && nominal > 0) {
            long long lo = nominal - nominal / 50;      // -2%
            long long hi = nominal + nominal / 50;      // +2%
            if (fps < lo) fps = lo;
            if (fps > hi) fps = hi;
            g_fps_eff.store(fps, std::memory_order_relaxed);
            g_fb_valid.store(true, std::memory_order_relaxed);
            // 每秒最多打一条，避免刷屏
            long long now = (long long)std::chrono::duration_cast<std::chrono::milliseconds>(
                    std::chrono::steady_clock::now().time_since_epoch()).count();
            long long prev = g_fb_last_log_ms.load(std::memory_order_relaxed);
            if (now - prev >= 1000 &&
                g_fb_last_log_ms.compare_exchange_strong(prev, now)) {
                double fbFrames = (double)raw / 65536.0;
                double ppm = (double)(fps - nominal) * 1e6 / (double)nominal;
                LOGI("feedback=%.4f 帧/µframe（标称 %.4f，偏差 %+.1f ppm）",
                     fbFrames, (double)nominal / 8000.0, ppm);
            }
        }
    }
    if (g_running && g_fb_xfer) {
        libusb_submit_transfer(g_fb_xfer);       // 立刻再挂一次（bRefresh 由端点自己节流）
    }
}

/** 打开并挂上反馈端点；失败只记一条日志，不影响播放（回落到标称帧率） */
static void start_feedback(uint8_t ep, int refresh) {
    g_ep_fb = ep;
    g_fb_interval = 1;
    g_fb_valid.store(false);
    g_fps_eff.store(0);
    if (ep == 0) return;
    g_fb_xfer = libusb_alloc_transfer(0);
    if (!g_fb_xfer) { LOGW("反馈端点分配失败，按标称帧率发送"); return; }
    libusb_fill_interrupt_transfer(g_fb_xfer, g_handle, ep, g_fb_buf,
                                   sizeof(g_fb_buf), on_feedback, nullptr,
                                   g_fb_interval);   // 0 = 端点自己的 bInterval
    int rc = libusb_submit_transfer(g_fb_xfer);
    if (rc != LIBUSB_SUCCESS) {
        LOGW("反馈端点 0x%02x 提交失败（%s），按标称帧率发送", ep, libusb_error_name(rc));
        libusb_free_transfer(g_fb_xfer);
        g_fb_xfer = nullptr;
        return;
    }
    LOGI("异步反馈端点已开: ep=0x%02x bRefresh=%d（每 %d 微帧刷新）", ep, refresh, 1 << (refresh & 7));
}

int ring_write(const uint8_t* data, int len) {
    long h = head.load(std::memory_order_relaxed);
    long free_space = kRingBytes - (h - tail.load(std::memory_order_acquire));
    int n = (int)std::min<long>(free_space, len);
    if (n <= 0) return 0;
    size_t off = (size_t)(h & (kRingBytes - 1));
    size_t first = std::min<size_t>(n, kRingBytes - off);
    memcpy(&ring[off], data, first);
    if (n > first) memcpy(&ring[0], data + first, n - first);
    head.store(h + n, std::memory_order_release);
    return n;
}
void reset_stats() {
    // 只清"欠载/错误"这类健康度计数：起播预填阶段的欠载发生在淡入之前（听不到），
    // 留在日志里会让人误判（真机复测时就看到一个恒定的 694 挂在那儿）。
    g_underruns.store(0, std::memory_order_relaxed);
    g_errors.store(0, std::memory_order_relaxed);
}

int ring_free() {
    return (int)(kRingBytes - (head.load() - tail.load()));
}
int ring_used() { return (int)(head.load() - tail.load()); }
/** 丢弃"尚未播放"的数据：读指针追平写指针（seek 专用，暂停不要用） */
void ring_discard() {
    tail.store(head.load(std::memory_order_acquire), std::memory_order_release);
}

void ring_reset() {
    head.store(0);
    tail.store(0);
}

static void cleanup();

bool start(int fd, const StreamInfo& info, int rate_hz, int frame_bytes,
           bool dsd_mode, bool dop_mode, std::string* err) {
    if (g_running) {
        if (err) *err = "泵已在运行";
        return false;
    }
    // 独立 ctx（与 uac2_raw 各自 wrap 同一 fd，libusb 文档允许）
    // 用 init_context 传 NO_DEVICE_DISCOVERY，避免隐式默认 context 去扫 /dev/bus/usb
    libusb_init_option opts[1];
    opts[0].option = LIBUSB_OPTION_NO_DEVICE_DISCOVERY;
    opts[0].value.ival = 1;
    int r = libusb_init_context(&g_ctx, opts, 1);
    if (r != LIBUSB_SUCCESS) {
        if (err) *err = "泵 libusb_init 失败";
        g_ctx = nullptr;
        return false;
    }
    r = libusb_wrap_sys_device(g_ctx, (intptr_t)fd, &g_handle);
    if (r != LIBUSB_SUCCESS) {
        if (err) *err = "泵 wrap fd 失败";
        return false;
    }
    g_info = info;
    g_ep = info.ep_out;
    g_maxpkt = info.max_packet;
    g_rate = rate_hz;
    g_frame_bytes = frame_bytes;
    g_dsd = dsd_mode;
    g_dop = dop_mode;
    // 泵**一启动就是静音态**：此时环形缓冲还是空的，静音分支不读缓冲、也不计欠载，
    // 上层可以在"有流但静音"的窗口里把缓冲预填到目标水位，再 set_mute(false) 淡入。
    // 以前这里是 gain=1/target=1，泵一起来就抢数据 → 第一包必然欠载（起播"咔"一声）。
    g_gain.store(0.0f);
    g_target.store(0.0f);
    g_silent.store(true);

    // 统计清零（否则上一次会话的欠载计数会让 Producer 误判断流）
    g_bytes.store(0);
    g_submitted.store(0);
    g_underruns.store(0);
    g_startup_underruns.store(0);
    g_first_data.store(false);
    g_errors.store(0);
    g_inflight.store(0);

    // 每秒字节数：
    //   PCM / DoP : rate × frame_bytes（rate 是 USB 采样率）
    //   DSD native: DSD 位率/4 —— 立体声 2 字节/帧、帧率 = 位率/8
    //               （漏掉 /4 会 8 倍超喂，环形缓冲瞬间被抽干 → 满屏欠载）
    long long bytes_per_sec = dsd_mode ? (long long)rate_hz / 4
                                       : (long long)rate_hz * frame_bytes;
    g_bytes_per_sec = bytes_per_sec;
    g_frame_bytes = frame_bytes > 0 ? frame_bytes : 8;
    g_frames_per_sec = bytes_per_sec / g_frame_bytes;   // 44100 / 48000 / 88200 / 176400(DoP) / 88200(DSD64)
    g_acc.store(0);
    g_mute.store(false);
    long mean_frames = (long)((g_frames_per_sec + 7999) / 8000);   // 单微帧最大帧数
    long mean = mean_frames * g_frame_bytes;
    int per_transfer = (int)(mean * kPktsPerXfer) + frame_bytes;
    per_transfer = std::min(per_transfer, g_maxpkt * kPktsPerXfer);

    if (mean <= 0) {
        if (err) *err = "无效的数据率（mean=0）";
        cleanup();
        return false;
    }
    if (mean > (long)g_maxpkt) {
        // 单个微帧超过端点包长：带宽不够（通常是采样率/位宽选高了）
        LOGW("数据率过高: %ld B/µframe > maxpkt %d，可能掉包", mean, g_maxpkt);
    }

    g_bufs.assign(kXfers, std::vector<uint8_t>(per_transfer));
    g_xfers.assign(kXfers, nullptr);
    for (int i = 0; i < kXfers; ++i) {
        g_xfers[i] = libusb_alloc_transfer(kPktsPerXfer);
        if (!g_xfers[i]) {
            if (err) *err = "alloc_transfer 失败";
            cleanup();
            return false;
        }
        libusb_fill_iso_transfer(g_xfers[i], g_handle, g_ep, g_bufs[i].data(),
                                 (int)(mean * kPktsPerXfer), kPktsPerXfer, on_done, nullptr, 0);
        g_xfers[i]->user_data = (void*)(long)i;
        int total = compute_lengths(g_xfers[i]);   // 预填的静音 URB 也按真实帧距排布
        g_xfers[i]->length = total;
        // 预填静音
        memset(g_bufs[i].data(), g_dsd ? 0x69 : 0x00, total);
        int rc = libusb_submit_transfer(g_xfers[i]);
        if (rc != LIBUSB_SUCCESS) {
            std::string msg = "submit_transfer 失败: ";
            msg += libusb_error_name(rc);
            if (err) *err = msg;
            cleanup();
            return false;
        }
        g_inflight.fetch_add(1);
    }
    g_running = true;
    // 反馈端点必须在 g_running=true 之后挂，回调里会立刻重新提交
    start_feedback(info.ep_fb, info.fb_refresh);
    g_evt = std::thread([] {
        while (g_running || g_inflight.load() > 0) {
            timeval tv{0, 50 * 1000};
            libusb_handle_events_timeout_completed(g_ctx, &tv, nullptr);
        }
    });
    // 打出精确均值与包长范围，避免"44 vs 44.1"这类读数误导（真机报告曾据此误判）
    {
        long long acc2 = 0; long flo = 1 << 30, fhi = 0;
        for (int i = 0; i < 8000; ++i) {
            acc2 += g_frames_per_sec;
            long f = acc2 / 8000;
            acc2 %= 8000;
            if (f < flo) flo = f;
            if (f > fhi) fhi = f;
        }
        LOGI("泵启动: %d xfers × %d pkts, %.4f 帧/µframe (%ld..%ld 帧 = %ld..%ld B), rate %d, %s, frame=%dB, 反馈端点=%s",
             kXfers, kPktsPerXfer, (double)g_frames_per_sec / 8000.0,
             flo, fhi, flo * g_frame_bytes, fhi * g_frame_bytes,
             rate_hz, dsd_mode ? "DSD" : "PCM", g_frame_bytes,
             info.ep_fb ? "有（异步，按反馈调帧数）" : "无（同步，按标称值）");
    }
    return true;
}

static void stop_feedback() {
    g_running = false;
    if (g_fb_xfer) {
        libusb_cancel_transfer(g_fb_xfer);
        g_fb_xfer = nullptr;
    }
    g_fb_valid.store(false);
    g_fps_eff.store(0);
}

static void cleanup() {
    for (auto* x : g_xfers)
        if (x) libusb_free_transfer(x);
    g_xfers.clear();
    g_bufs.clear();
    if (g_handle) {
        libusb_close(g_handle);
        g_handle = nullptr;
    }
    if (g_ctx) {
        libusb_exit(g_ctx);
        g_ctx = nullptr;
    }
}

void stop() {
    if (!g_running) {
        cleanup();   // start() 半途失败时也要回收
        return;
    }
    // 先取消反馈端点（也让它别在回调里重新提交），事件线程处理完取消后线程退出
    if (g_fb_xfer) {
        libusb_cancel_transfer(g_fb_xfer);
    }
    g_running = false;
    for (auto* x : g_xfers)
        if (x) libusb_cancel_transfer(x);
    if (g_evt.joinable()) g_evt.join();
    g_evt = std::thread();   // 复位，允许下次 start 再 join
    if (g_fb_xfer) {
        libusb_free_transfer(g_fb_xfer);
        g_fb_xfer = nullptr;
    }
    g_fb_valid.store(false);
    g_fps_eff.store(0);
    g_ep_fb = 0;
    cleanup();
    LOGI("泵停止");
}

bool is_running() { return g_running; }

/**
 * seek 后冲刷缓冲：旧位置的数据必须丢掉，否则会先把缓冲里剩下的旧声音播完（≈3 s）。
 * PCM 走"淡出 → 丢弃 → 淡入"，避免丢弃瞬间的波形跳变；DSD/DoP 直接丢。
 *
 * ★ 这个序列是**跨微帧**的，调用方必须等它做完再发"淡入"，否则淡入会把淡出状态
 *   直接盖掉（真机日志：`seek：开始淡出并准备丢弃` 之后 1 ms 就是 `开始淡入`，
 *   结果淡出没走完、缓冲没丢 → 列表点歌时旧歌继续放 ≈2.95 s）。
 *   需要等待请用 seek_flush_blocking()。
 */
void seek_flush() {
    if (!g_running) { ring_discard(); return; }
    g_pending_discard.store(true);
    g_target.store(0.0f);
    LOGI("泵 seek：开始淡出并准备丢弃旧缓冲（水位 %d）", ring_used());
    if (g_dsd || g_dop) {
        g_gain.store(0.0f);
        ring_discard();
        g_pending_discard.store(false);
        g_target.store(1.0f);
        g_gain.store(1.0f);
        LOGI("泵 seek：已丢弃旧缓冲（DSD 直切）");
    }
}

/**
 * 阻塞版 seek_flush：淡出 → 丢弃 → **返回时已丢弃完成**（不含淡入）。
 * 播放线程在 seek 后调用它，再继续写新位置的数据，最后才 set_mute(false) 淡入。
 *
 * @param timeout_ms 最长等待（PCM 淡出约 10 ms，给 6 倍余量）
 * @return true=已丢弃；false=超时（调用方仍会继续，但会记一条 warn）
 */
bool seek_flush_blocking(int timeout_ms) {
    if (!g_running) { ring_discard(); return true; }
    seek_flush();                       // 置 pending + 目标 gain=0（DSD/DoP 在这里已直接丢弃）
    if (!g_pending_discard.load(std::memory_order_relaxed)) return true;
    const auto deadline = std::chrono::steady_clock::now() +
                          std::chrono::milliseconds(timeout_ms);
    while (g_pending_discard.load(std::memory_order_relaxed)) {
        if (std::chrono::steady_clock::now() > deadline) {
            // 超时兜底：泵可能已经停了（拔线/停止），直接丢，避免"旧数据永远不丢"
            ring_discard();
            g_pending_discard.store(false);
            g_silent.store(true);
            g_gain.store(0.0f);
            g_target.store(0.0f);
            LOGW("泵 seek：等待淡出超时（%d ms），强制丢弃", timeout_ms);
            return false;
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }
    return true;
}

void set_mute(bool mute) {
    // 不清环形缓冲：里面是"尚未播放"的音频，清掉会让恢复跳过最多 1MB（≈3 s）
    g_mute.store(mute);
    if (g_dop || g_dsd) {
        // DSD/DoP 的载荷是位流，不能乘增益 → 直接切静音码
        g_gain.store(mute ? 0.0f : 1.0f);
        g_silent.store(mute);
        LOGI("泵 %s（DSD 静音码）", mute ? "静音（暂停）" : "恢复输出");
    } else {
        if (mute) {
            // 暂停**不**丢弃环形缓冲（第二十二轮约定：只有 seek/切歌才丢）。
            // 若此刻正好有 seek 的 pending discard，也跟着这次淡出一起处理，
            // 但 pump 会看到 g_mute 而保持静音（见 pump 里的分支）。
            g_target.store(0.0f);          // 触发淡出；g_silent 由斜坡结束置位
            LOGI("泵 开始淡出（10ms 斜坡，当前 gain=%.3f）",
                 g_gain.load(std::memory_order_relaxed));
        } else {
            g_silent.store(false);         // 允许重新读缓冲
            g_target.store(1.0f);
            LOGI("泵 开始淡入（10ms 斜坡，当前 gain=%.3f）",
                 g_gain.load(std::memory_order_relaxed));
        }
    }
}

Stats stats() {
    return Stats{g_submitted.load(), g_bytes.load(), g_underruns.load(),
                 g_errors.load(), g_startup_underruns.load()};
}

} // namespace usbaudio
