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

// 前置声明：定义在 on_feedback 之后（usbaudio 作用域），此处保证匿名命名空间内也可见。
static bool submit_feedback_once();

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
int g_fb_attr = 0;
int g_fb_maxpkt = 4;
int g_fb_retry = 0;
bool g_fb_iso = true;
/** 反馈提交失败后挂起，由音频 URB 的完成回调驱动重试（有些 DAC 要等流跑起来才肯应答反馈） */
std::atomic<bool> g_fb_pending{false};
std::atomic<int> g_fb_attempts{0};
std::atomic<long long> g_fb_next_try_ms{0};
/** 一次反馈 URB 里装几个包：UAC2 的 bInterval 是"每 2^(bInterval-1) 微帧一个反馈包"，
 *  凑成一串提交既对齐设备节奏，也少 8 倍的重提交次数（单包 URB 每微帧重提交一次，
 *  多数固件在这节奏下根本不回数据 —— 真机第一百二十八轮整段日志一条反馈值都没有）。 */
constexpr int kFbPackets = 8;
libusb_transfer* g_fb_xfer = nullptr;
uint8_t g_fb_buf[kFbPackets * 4] = {0};
std::atomic<bool> g_fb_valid{false};      // 收到过有效反馈
std::atomic<long long> g_fps_eff{0};      // 由反馈换算出的"每秒帧数"（0 = 用标称值）
std::atomic<long long> g_fb_last_log_ms{0};
// ── 可观测性计数器（真机第一百二十八轮："反馈端点已开，但一条反馈值都没有"）──
std::atomic<long long> g_fb_submits{0};      // 提交次数
std::atomic<long long> g_fb_dones{0};        // 完成回调次数
std::atomic<long long> g_fb_zero{0};         // 完成但 0 字节（设备没给数据）
std::atomic<long long> g_fb_nonzero{0};      // 完成且拿到数据
std::atomic<long long> g_fb_last_raw{0};     // 最近一次原始值
std::atomic<int> g_fb_last_len{0};           // 最近一次实际字节数
std::atomic<long long> g_fb_nominal_seen{0}; // 最近一次折算出的标称对比（fps）
std::atomic<bool> g_fb_no_device{false};     // 设备已消失：别再重试
// ── 口径对齐（真机第一百三十一轮把"实发偏负"误读成码率少发，其实两个时钟域不同）──
// 我们的发送节奏由**USB 微帧**驱动（1 包 = 1 微帧，与主机控制器时钟同步），
// 而"每秒应发多少"是拿手机 CPU 的 CLOCK_MONOTONIC 算的。两者相差几 ppm~上百 ppm 很正常，
// 所以必须同时打印"USB 微帧数 vs 壁钟毫秒"，否则 ppm 对账没有意义。
std::atomic<long long> g_packets{0};          // 已提交的等时包数（= 微帧数）
std::atomic<long long> g_frames_asked{0};     // compute_lengths 算出的帧数（累计）
std::atomic<long long> g_start_ms{0};         // 开流时刻（CLOCK_MONOTONIC 毫秒）
// 目标速率用**毫帧/秒**存（1000 倍）：反馈平滑值是 95999.5 这种小数，取整到 96000 会凭空
// 加 5 ppm（真机第一百三十四轮实测"采用 96000（+0.0 ppm）"就是这么来的）。
std::atomic<long long> g_fps_x1000{0};        // 0 = 用标称值
std::atomic<long long> g_submit_fails{0};     // 音频 URB 提交失败次数
std::atomic<long long> g_urbs_done{0};        // 完成的音频 URB 数（×kPktsPerXfer = 微帧）
std::atomic<int> g_submit_fail_last{0};       // 最近一次失败的 libusb 错误码
/** 提交失败的 URB：留在这里由下一次完成回调重新武装（丢掉 1 ms 音频 = 一次可闻的断点） */
std::atomic<bool> g_xfer_down[kXfers] = {};
// 反馈值低通：每次完成覆盖 8 微帧 = 1 ms，alpha = 1ms / 500ms
constexpr double kFbSmoothAlpha = 0.002;
double g_fb_smooth = 0.0;                     // 仅事件线程读写
// 每个汇总窗口的算术均值（设备给的是量化值，均值才是真实诉求）
double g_win_sum_fps = 0.0;
long long g_win_n = 0;

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
    // ★ 累加器单位是"毫帧"（1/1000 帧）：目标速率 95999.5 帧/秒 这种小数必须保留，
    //   取整成 96000 会凭空多送 5 ppm（真机第一百三十四轮就是踩这个：采用 96000 / +0.0 ppm）。
    long long acc = g_acc.load(std::memory_order_relaxed);
    long long framesSum = 0;
    // 有反馈就用反馈换算出的速率（已 clamp 到 ±2%），没有就用标称值
    long long fpsx = g_fps_x1000.load(std::memory_order_relaxed);
    if (fpsx <= 0) fpsx = (long long)g_frames_per_sec * 1000LL;
    int total = 0;
    for (int i = 0; i < kPktsPerXfer; ++i) {
        acc += fpsx;
        long frames = (long)(acc / 8000000LL);
        acc %= 8000000LL;
        if (frames < 0) frames = 0;
        long len = frames * g_frame_bytes;
        if (len > g_maxpkt) {
            frames = g_maxpkt / (g_frame_bytes ? g_frame_bytes : 8);
            len = frames * g_frame_bytes;
        }
        x->iso_packet_desc[i].length = (int)len;
        total += (int)len;
        framesSum += frames;
    }
    g_acc.store(acc, std::memory_order_relaxed);
    g_packets.fetch_add(kPktsPerXfer, std::memory_order_relaxed);
    g_frames_asked.fetch_add(framesSum, std::memory_order_relaxed);
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
    // 提交失败 = 这 1 ms 音频直接消失（DAC 侧就是一次断点 → 「嗞」）。
    // 内联重试 3 次（每次 150 µs，最多 450 µs，远小于 6 个 URB 的缓冲余量）；
    // 仍失败就记进掉队表，由下一次完成回调重新武装 —— 绝不把这一毫秒丢掉。
    int rc = libusb_submit_transfer(xfer);
    for (int attempt = 0; rc != LIBUSB_SUCCESS && attempt < 3 && g_running; ++attempt) {
        std::this_thread::sleep_for(std::chrono::microseconds(150));
        rc = libusb_submit_transfer(xfer);
    }
    if (rc != LIBUSB_SUCCESS) {
        int idx = (int)(long)xfer->user_data;
        g_submit_fails.fetch_add(1, std::memory_order_relaxed);
        g_submit_fail_last.store(rc, std::memory_order_relaxed);
        g_errors.fetch_add(1, std::memory_order_relaxed);
        if (idx >= 0 && idx < kXfers) g_xfer_down[idx].store(true, std::memory_order_relaxed);
        /* 注意：这里**不**减 inflight —— 它会在 on_done 里被重新武装后重新计入 */
        return;
    }
    int idx2 = (int)(long)xfer->user_data;
    if (idx2 >= 0 && idx2 < kXfers) g_xfer_down[idx2].store(false, std::memory_order_relaxed);
    g_submitted.fetch_add(1, std::memory_order_relaxed);
    g_bytes.fetch_add(total, std::memory_order_relaxed);
}

/** 反馈端点重试：由音频 URB 完成回调驱动（每 100ms 最多一次，最多 30 次） */
static void maybe_retry_feedback() {
    if (!g_fb_pending.load(std::memory_order_relaxed) || !g_running || !g_fb_xfer) return;
    if (g_fb_no_device.load(std::memory_order_relaxed)) return;   // 设备已消失，等下次开流
    long long now = (long long)std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now().time_since_epoch()).count();
    long long next = g_fb_next_try_ms.load(std::memory_order_relaxed);
    if (now < next) return;
    g_fb_next_try_ms.store(now + 100);
    int n = g_fb_attempts.fetch_add(1) + 1;
    if (n > 30) {
        g_fb_pending.store(false);
        g_fps_eff.store(0);
        g_fps_x1000.store(0);
        LOGW("反馈端点重试 30 次仍失败：退化为标称帧率（异步反馈不可用）");
        return;
    }
    g_fb_iso = !g_fb_iso;      // 两种传输类型轮流试
    if (submit_feedback_once()) {
        g_fb_pending.store(false);
        LOGI("异步反馈端点重试成功（第 %d 次，%s 模式）", n, g_fb_iso ? "iso" : "interrupt");
    }
}

void on_done(libusb_transfer* xfer) {
    if (!g_running || xfer->status == LIBUSB_TRANSFER_CANCELLED ||
        xfer->status == LIBUSB_TRANSFER_NO_DEVICE) {
        g_inflight.fetch_sub(1);
        return;
    }
    g_urbs_done.fetch_add(1, std::memory_order_relaxed);
    maybe_retry_feedback();
    if (xfer->status != LIBUSB_TRANSFER_COMPLETED) {
        g_errors.fetch_add(1, std::memory_order_relaxed);
    }
    pump(xfer);
    // 把提交失败的 URB 重新武装（它们不在飞行中，inflight 也没减，只要成功就补回计数）
    for (int i = 0; i < kXfers; ++i) {
        if (!g_xfer_down[i].load(std::memory_order_relaxed)) continue;
        libusb_transfer* t = g_xfers[i];
        if (!t) continue;
        int rc = libusb_submit_transfer(t);
        if (rc == LIBUSB_SUCCESS) {
            g_xfer_down[i].store(false, std::memory_order_relaxed);
            g_submitted.fetch_add(1, std::memory_order_relaxed);
            g_bytes.fetch_add((long long)t->length, std::memory_order_relaxed);
            static std::atomic<int> rearm_logged{0};
            if (rearm_logged.fetch_add(1) < 3) LOGI("掉队的音频 URB 已重新武装（slot %d）", i);
        } else {
            g_submit_fail_last.store(rc, std::memory_order_relaxed);
        }
    }
}

} // namespace

/**
 * 反馈端点回调：解码定点值，换算成"每秒帧数"供 compute_lengths 用。
 *
 * 真机踩过两种编码，都要认：
 *   · UAC2 explicit feedback：4 字节，**16.16**，单位"样本/微帧" → fps = raw/65536*8000
 *   · UAC1 遗留固件：3 字节，**10.14**，单位"样本/帧(1ms)" → fps = raw/16384*1000
 * 两种都算，取落在标称 ±10% 内的那个；都不在内 → 打 WARN 并退回标称帧率，绝不把坏反馈
 * 灌进帧长计算。每 30 s 打一条汇总（含原始值 / 提交与完成次数 / 实发帧数 vs 标称 / ppm），
 * 这样"反馈到底有没有参与调速"一眼可判。
 *
 * ★ 回调运行在 libusb 事件线程上：这里**绝对不能 sleep**（会让音频 URB 饿死 → 嗞），
 *   重试交给 maybe_retry_feedback()（由音频 URB 完成驱动）。
 */
static void on_feedback(libusb_transfer* t) {
    static std::atomic<long long> last_bad_log{0};
    static std::atomic<long long> last_sum_ms{0};
    static std::atomic<long long> sum_bytes0{0};
    static std::atomic<long long> win_pkts0{0};
    static std::atomic<long long> win_urbs0{0};
    static std::atomic<long long> win_diff0{0};
    static std::atomic<long long> win_bytes0{0};
    static std::atomic<long long> sum_ms0{0};
    auto now_ms = [] {
        return (long long)std::chrono::duration_cast<std::chrono::milliseconds>(
                std::chrono::steady_clock::now().time_since_epoch()).count();
    };
    g_fb_dones.fetch_add(1, std::memory_order_relaxed);

    // 数据可能在任意一个包里（多包 URB）；libusb 正常会把长度汇总到 actual_length，
    // 但个别版本只填 iso_packet_desc[].actual_length —— 两个都看。
    int len = t->actual_length;
    int pkt = -1;
    if (len <= 0) {
        for (int i = 0; i < t->num_iso_packets; ++i) {
            int l = t->iso_packet_desc[i].actual_length;
            if (l > 0) { len = l; pkt = i; break; }
        }
    }
    if (len > 0) {
        const uint8_t* d = (pkt >= 0)
                ? (t->buffer + pkt * (int)t->iso_packet_desc[pkt].length)
                : t->buffer;
        // 取 4 字节（3 字节值补 0）
        uint32_t raw = (uint32_t)d[0];
        if (len > 1) raw |= (uint32_t)d[1] << 8;
        if (len > 2) raw |= (uint32_t)d[2] << 16;
        if (len > 3) raw |= (uint32_t)d[3] << 24;
        g_fb_last_raw.store((long long)raw, std::memory_order_relaxed);
        g_fb_last_len.store(len, std::memory_order_relaxed);
        long long nominal = g_frames_per_sec;
        if (nominal > 0) {
            double n = (double)nominal;
            double v_1616 = (double)raw / 65536.0 * 8000.0;   // UAC2：16.16 样本/微帧
            double v_1014 = (double)raw / 16384.0 * 1000.0;   // UAC1 遗留：10.14 样本/帧
            bool ok_1616 = v_1616 > n * 0.9 && v_1616 < n * 1.1;
            bool ok_1014 = v_1014 > n * 0.9 && v_1014 < n * 1.1;
            double pick = ok_1616 ? v_1616 : (ok_1014 ? v_1014 : -1.0);
            if (pick > 0) {
                // 一阶低通（时间常数 ≈0.5 s）：设备给的是量化过的值
                //（真机 96k 上只有 0xbfff8 / 0xc0000 两种，1 LSB ≈ 1.27 ppm，跳一次 ≈10 ppm），
                //直接拿单次值当目标会让调速来回横跳。平滑后再取整成整数帧/秒。
                g_fb_smooth = (g_fb_smooth <= 0.0) ? pick
                                                   : g_fb_smooth + (pick - g_fb_smooth) * kFbSmoothAlpha;
                // 窗口算术均值：设备只给量化过的两档值，均值才是它真正的诉求
                if (g_win_sum_fps <= 0.0) g_win_sum_fps = pick; else g_win_sum_fps += pick;
                g_win_n++;
                double target = g_fb_smooth;
                double lo = (double)nominal * 0.98, hi = (double)nominal * 1.02;   // ±2%
                if (target < lo) target = lo;
                if (target > hi) target = hi;
                // ★ 毫帧精度存下来（95999.5 不能取整成 96000）
                g_fps_x1000.store((long long)(target * 1000.0 + 0.5), std::memory_order_relaxed);
                g_fps_eff.store((long long)(target + 0.5), std::memory_order_relaxed);
                g_fb_valid.store(true, std::memory_order_relaxed);
                g_fb_nonzero.fetch_add(1, std::memory_order_relaxed);
            } else {
                g_fb_nonzero.fetch_add(1, std::memory_order_relaxed);
                long long now = now_ms();
                long long prev = last_bad_log.load(std::memory_order_relaxed);
                if (now - prev >= 5000 && last_bad_log.compare_exchange_strong(prev, now)) {
                    LOGW("反馈值不可信: len=%d raw=0x%08x（16.16 解释=%.0f 帧/秒，10.14 解释=%.0f 帧/秒，"
                         "标称 %lld）→ 仍按标称帧率",
                         len, raw, v_1616, v_1014, nominal);
                }
            }
        }
    } else {
        g_fb_zero.fetch_add(1, std::memory_order_relaxed);
    }

    // 每 30 s 一条汇总：这是判断"反馈有没有真的参与调速"的唯一可靠依据
    {
        long long now = now_ms();
        long long prev = last_sum_ms.load(std::memory_order_relaxed);
        if (now - prev >= 30000 && last_sum_ms.compare_exchange_strong(prev, now)) {
            long long bytes = g_bytes.load(std::memory_order_relaxed);
            long long prevBytes = sum_bytes0.exchange(bytes, std::memory_order_relaxed);
            long long prevMs = sum_ms0.exchange(now, std::memory_order_relaxed);
            long long spanMs = prevMs > 0 ? (now - prevMs) : 0;
            long long nominal = g_frames_per_sec;
            long long fb = g_fps_eff.load(std::memory_order_relaxed);
            double ppm = 0.0, actualFrames = 0.0, nominalFrames = 0.0;
            if (spanMs > 0 && g_frame_bytes > 0 && nominal > 0) {
                actualFrames = (double)(bytes - prevBytes) / (double)g_frame_bytes;
                nominalFrames = (double)nominal * (double)spanMs / 1000.0;
                if (nominalFrames > 0.0)
                    ppm = (actualFrames - nominalFrames) * 1e6 / nominalFrames;
            }
            // 双时钟口径：发送节奏由 USB 微帧驱动，而"标称"是拿 CPU 壁钟算的。
            // 两者差几 ppm~上百 ppm 都很正常，不把这两个数同时打出来，"实发 ppm" 无法解释。
            long long pkts = g_packets.load(std::memory_order_relaxed);
            long long startMs = g_start_ms.load(std::memory_order_relaxed);
            double ufPpm = 0.0;
            if (startMs > 0) {
                long long wallMs = now - startMs;
                long long uf = pkts;                        // 1 包 = 1 微帧
                double expUf = (double)wallMs * 8.0;
                if (expUf > 0.0) ufPpm = ((double)uf - expUf) * 1e6 / expUf;
            }
            long long asked = g_frames_asked.load(std::memory_order_relaxed);
            long long sent = g_frame_bytes > 0
                    ? (long long)(g_bytes.load(std::memory_order_relaxed) / g_frame_bytes) : 0;
            // 分两行打：真机日志里一整行太长容易被截断（第一百三十三轮就只拿到半行）
            // ★ 判据只有一条："累计 算 − 送" 必须恒为 0 —— 它证明发送节奏完全由
            //   **微帧累加器**驱动，与壁钟无关（壁钟只用来做诊断对比）。
            LOGI("反馈汇总A %.1fs：提交 %lld / 完成 %lld（0字节 %lld，有值 %lld）｜"
                 "raw=0x%llx=%lld len=%d｜平滑 %.5f 帧/µframe｜采用 %.3f 帧/秒（%+.1f ppm）",
                 spanMs > 0 ? spanMs / 1000.0 : 30.0,
                 (long long)g_fb_submits.load(std::memory_order_relaxed),
                 (long long)g_fb_dones.load(std::memory_order_relaxed),
                 (long long)g_fb_zero.load(std::memory_order_relaxed),
                 (long long)g_fb_nonzero.load(std::memory_order_relaxed),
                 (long long)g_fb_last_raw.load(std::memory_order_relaxed),
                 (long long)g_fb_last_raw.load(std::memory_order_relaxed),
                 g_fb_last_len.load(std::memory_order_relaxed),
                 g_fb_smooth > 0.0 ? g_fb_smooth / 8000.0 : 0.0,   // 帧/µframe
                 (double)g_fps_x1000.load(std::memory_order_relaxed) / 1000.0,
                 nominal > 0 ? ((double)g_fps_x1000.load(std::memory_order_relaxed) / 1000.0
                                - (double)nominal) * 1e6 / (double)nominal : 0.0);
            // 窗口内的微帧增量 vs 壁钟时长：常量偏移（例如开流瞬间）不会污染 ppm
            long long bytesNow = g_bytes.load(std::memory_order_relaxed);
            long long bytesWin = bytesNow - win_bytes0.exchange(bytesNow, std::memory_order_relaxed);
            long long pkts0 = win_pkts0.exchange(pkts, std::memory_order_relaxed);
            double winPktPpm = 0.0;
            long long winMs = spanMs;
            if (winMs > 0) {
                double expPk = (double)winMs * 8.0;
                if (expPk > 0.0) winPktPpm = ((double)(pkts - pkts0) - expPk) * 1e6 / expPk;
            }
            double winMean = (g_win_n > 0) ? g_win_sum_fps / (double)g_win_n : 0.0;
            long long winN = g_win_n;
            g_win_sum_fps = 0.0;
            g_win_n = 0;
            long long urbs = g_urbs_done.load(std::memory_order_relaxed);
            long long urbsWin = urbs - win_urbs0.exchange(urbs, std::memory_order_relaxed);
            long long ufWin = urbsWin * kPktsPerXfer;          // 1 URB = kPktsPerXfer 微帧
            long long diffNow = asked - sent;
            long long diffDelta = diffNow - win_diff0.exchange(diffNow, std::memory_order_relaxed);
            double winSentPerUf = (ufWin > 0 && bytesWin > 0)
                    ? (bytesWin / (double)g_frame_bytes) / (double)ufWin : 0.0;
            LOGI("反馈汇总B：累计 算=%lld 送=%lld 差=%lld（Δ=%lld/窗口，应恒为 0）｜"
                 "窗口完成 URB %lld（微帧 %lld，壁钟 %lldms，%+.1f ppm）｜"
                 "窗口每微帧实发 %.6f 帧（目标 %.6f）｜窗口均值 %.3f 帧/秒（%+.1f ppm，%lld 样本）｜"
                 "提交失败 %lld（%s）｜环 %lldKB",
                 asked, sent, diffNow, diffDelta,
                 urbsWin, ufWin, winMs, winPktPpm,
                 winSentPerUf,
                 (double)g_fps_x1000.load(std::memory_order_relaxed) / 8000000.0,
                 winMean,
                 nominal > 0 ? (winMean - (double)nominal) * 1e6 / (double)nominal : 0.0,
                 winN,
                 (long long)g_submit_fails.load(std::memory_order_relaxed),
                 libusb_error_name(g_submit_fail_last.load(std::memory_order_relaxed)),
                 (long long)((head.load(std::memory_order_relaxed) -
                              tail.load(std::memory_order_relaxed)) / 1024));
        }
    }

    if (g_running && g_fb_xfer && !g_fb_no_device.load(std::memory_order_relaxed)) {
        if (!submit_feedback_once()) {
            // 不在这里重试（会阻塞事件线程）：交给音频 URB 完成回调按 100ms 节奏重试
            g_fb_pending.store(true, std::memory_order_relaxed);
            g_fb_next_try_ms.store(0, std::memory_order_relaxed);
        } else {
            g_fb_retry = 0;
        }
    }
}

/**
 * 提交一次反馈 URB。
 *
 * UAC2 的 explicit feedback 端点在描述符里通常是 **isochronous**（bmAttributes & 0x03 == 1），
 * 真机第一百二十六轮就是踩在这里：用 interrupt 提交 → usbfs 直接回 `LIBUSB_ERROR_IO`，
 * 而且当时只试一次就永久退化成标称帧率（等于异步反馈从未生效）。
 * 现在：按描述符的类型选 transfer（iso 优先，失败再试 interrupt），并带重试。
 */
static bool submit_feedback_once() {
    if (!g_handle || !g_fb_xfer) return false;
    libusb_transfer* t = g_fb_xfer;
    int pktLen = g_fb_maxpkt > 0 ? g_fb_maxpkt : 4;
    if (pktLen > 4) pktLen = 4;                    // UAC2 反馈值最多 4 字节
    int reqLen = pktLen * kFbPackets;
    if (reqLen > (int)sizeof(g_fb_buf)) reqLen = (int)sizeof(g_fb_buf);
    if (g_fb_iso) {
        // ★ 多包：按设备 bInterval 的节奏成串提交。单包 URB（每微帧重提交一次）时，
        //   真机上设备一个反馈字节都不回（第一百二十八轮整段日志无任何反馈值）。
        int total = reqLen;
        int pkts = total / pktLen;
        libusb_fill_iso_transfer(t, g_handle, g_ep_fb, g_fb_buf, total, pkts,
                                 on_feedback, nullptr, 100);
        for (int i = 0; i < pkts; ++i) t->iso_packet_desc[i].length = pktLen;
    } else {
        // interrupt 传输一次一个包：长度必须 ≤ wMaxPacketSize，否则 usbfs 直接回 EINVAL
        libusb_fill_interrupt_transfer(t, g_handle, g_ep_fb, g_fb_buf, pktLen,
                                       on_feedback, nullptr, 0);
        reqLen = pktLen;
    }
    g_fb_submits.fetch_add(1, std::memory_order_relaxed);
    int rc = libusb_submit_transfer(t);
    if (rc == LIBUSB_ERROR_NO_DEVICE) {
        // 设备已消失（拔线 / 重开流窗口）：这不是"反馈不可用"，也不该消耗重试预算
        g_fb_no_device.store(true, std::memory_order_relaxed);
        g_fb_pending.store(false, std::memory_order_relaxed);
        LOGW("反馈端点 0x%02x 提交失败（LIBUSB_ERROR_NO_DEVICE）→ 设备已消失，本次流不再重试反馈",
             g_ep_fb);
        return false;
    }
    if (rc != LIBUSB_SUCCESS) {
        // 重试时同一条会重复几十次：前 3 次 + 之后每 10 次打一条，避免刷屏
        static std::atomic<int> fails{0};
        int n = fails.fetch_add(1) + 1;
        if (n <= 3 || (n % 10) == 0) {
            LOGW("反馈端点 0x%02x 提交失败（%s，%s模式，len=%d attr=0x%02x maxpkt=%d "
                 "interval=%d，累计 %d 次）",
                 g_ep_fb, libusb_error_name(rc), g_fb_iso ? "iso" : "interrupt",
                 reqLen, g_fb_attr, g_fb_maxpkt, g_fb_interval, n);
        }
        return false;
    }
    return true;
}

/** 打开并挂上反馈端点：iso 优先、失败退 interrupt、各试 retry 次；全失败才回落到标称帧率 */
static void start_feedback(uint8_t ep, int refresh, int attr, int maxpkt, int interval) {
    g_ep_fb = ep;
    g_fb_interval = interval;
    g_fb_attr = attr;
    g_fb_maxpkt = maxpkt > 0 ? maxpkt : 4;
    g_fb_valid.store(false);
    g_fps_eff.store(0);
    g_fb_no_device.store(false);
    // 每次开流都清零：上一条流的计数/失败历史不能带过来（否则"累计 2 次"就放弃）
    g_fb_submits.store(0);
    g_fb_dones.store(0);
    g_fb_zero.store(0);
    g_fb_nonzero.store(0);
    g_fb_last_raw.store(0);
    g_fb_last_len.store(0);
    g_fb_attempts.store(0);
    g_fb_retry = 0;
    g_fb_smooth = 0.0;
    if (ep == 0) return;
    // 描述符里是 isochronous（位 0-1 == 1）就先用 iso；否则先试 interrupt
    g_fb_iso = ((attr & 0x03) == 0x01);
    if (!g_fb_xfer) {
        // ★ 必须按"最长可能用到"的 iso 包数分配：libusb_fill_iso_transfer 不会重新分配
        //   包描述符数组，包数不够就是越界写。
        g_fb_xfer = libusb_alloc_transfer(kFbPackets);
        if (!g_fb_xfer) { LOGW("反馈端点分配失败，按标称帧率发送"); return; }
    }
    if (submit_feedback_once()) {
        int periodUf = interval > 0 ? (1 << ((interval - 1) & 7)) : 1;
        LOGI("异步反馈端点已开: ep=0x%02x %s attr=0x%02x maxpkt=%d interval=%d bRefresh=%d"
             "（每 %d 微帧一个反馈包 ≈ %.2f ms，本次 URB %d 包）",
             ep, g_fb_iso ? "iso" : "interrupt", attr, g_fb_maxpkt, interval, refresh,
             periodUf, (double)periodUf / 8.0, kFbPackets);
        return;
    }
    // 首次失败不放弃：有些固件要等音频流真的跑起来（有 URB 在飞）才接受反馈端点。
    // 交给 on_done()（音频 URB 完成）驱动重试，两种传输类型轮流试。
    g_fb_iso = !g_fb_iso;
    g_fb_pending.store(true);
    g_fb_attempts.store(1);
    g_fb_next_try_ms.store(0);
    LOGI("反馈端点 0x%02x 首次提交失败，等音频流起来后重试（最多 30 次）", ep);
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
    g_packets.store(0);
    g_frames_asked.store(0);
    g_fps_x1000.store(0);
    g_submit_fails.store(0);
    g_submit_fail_last.store(0);
    for (auto& d : g_xfer_down) d.store(false);
    g_start_ms.store((long long)std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now().time_since_epoch()).count());

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
        // ★ 这 6 个预填 URB 的帧数在上面的 compute_lengths 里已经计入 g_frames_asked/g_packets，
        //   这里必须同样计入"送出"，否则「累计 算 − 送」会永远差 kXfers 个 URB
        //   （真机第一百三十五轮看到的恒定 576 帧 = 6 × 96 帧 @96k 就是这个）。
        g_submitted.fetch_add(1);
        g_bytes.fetch_add(total, std::memory_order_relaxed);
    }
    g_running = true;
    // 反馈端点必须在 g_running=true 之后挂，回调里会立刻重新提交
    start_feedback(info.ep_fb, info.fb_refresh, info.fb_attr, info.fb_maxpkt, info.fb_interval);
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
        if (g_fb_xfer) {          // start() 半途失败/重复 stop：反馈 transfer 也要回收
            libusb_cancel_transfer(g_fb_xfer);
            libusb_free_transfer(g_fb_xfer);
            g_fb_xfer = nullptr;
        }
        g_fb_valid.store(false);
        g_fps_eff.store(0);
        g_fb_pending.store(false);
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
    g_fps_x1000.store(0);
    g_fb_pending.store(false);
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
