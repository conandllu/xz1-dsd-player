// SPDX-License-Identifier: GPL-3.0-or-later
// usb_stream.h — native 等时（ISO）输出泵
//
// 预提交 N 个大 ISO 传输（每个含多个 125µs 微帧包），回调里从 PCM/DSD
// 字节环形缓冲取数据重提交，形成自驱动泵。环形缓冲空时输出静音
// （PCM 0x00；DSD 0x69 —— DSD 静音规范值），保证流不断。
// 采样率由 Kotlin 侧的 ClockPuller 微调（ASRC），native 只管搬运。
#pragma once
#include <cstdint>
#include <string>
#include "uac2_raw.h"
using uac2::StreamInfo;

namespace usbaudio {

bool start(int fd, const StreamInfo& info, int rate_hz, int frame_bytes,
           bool dsd_mode, bool dop_mode, std::string* err);
void stop();
bool is_running();
/** 暂停/恢复：暂停时泵改送静音帧（保持等时节奏），并清空环形缓冲 */
void set_mute(bool mute);

// 环形缓冲写入（Kotlin producer 线程调用，非阻塞，可能部分写入）
int ring_write(const uint8_t* data, int len);
int ring_free();
int ring_used();
void ring_reset();
/** 丢掉未播放数据（seek 专用；读指针追平写指针） */
void ring_discard();
/** seek 后冲刷：淡出 → 丢弃 → 淡入（异步，不等完成） */
void seek_flush();
/**
 * seek 后冲刷的**阻塞**版本：返回时缓冲已丢弃（不含淡入）。
 * 调用方必须先等它返回，再发 set_mute(false)，否则淡入会覆盖淡出、旧数据不丢。
 */
bool seek_flush_blocking(int timeout_ms);

struct Stats {
    long submitted;
    long bytes;
    int underruns;
    int errors;
};
Stats stats();

} // namespace usbaudio
