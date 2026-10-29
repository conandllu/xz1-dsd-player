// SPDX-License-Identifier: GPL-3.0-or-later
// uac2_raw.h — UAC2 设备发现 / 独占声明 / 流格式（PCM / DSD Native）能力探测
//
// 独占输出原理：libusb 走 usbfs 直接 claim AC/AS 接口（先把内核 snd-usb-audio
// 驱动 detach），此后系统 AudioFlinger 无法再使用该 DAC —— 即"独占"。
// 关闭时必须 release_interface + attach_kernel_driver，恢复系统输出。
#pragma once
#include <cstdint>
#include <string>
#include <vector>

namespace uac2 {

struct AltInfo {
    int alt;            // bAlternateSetting
    int format_type;    // 1 = PCM FORMAT_TYPE_I, 4 = GENERIC（DSD native）
    int subslot_size;   // 每声道子槽字节数
    int bit_res;        // 有效位宽
    int dsd_rate;       // GENERIC 格式声明的 DSD 位率（Hz，8192 的倍数），非 DSD 为 0
    int channels;
    int if_num = -1;          // 所属 AS 接口号
    std::vector<int> rates;   // ★ 该 alt **自己**声明的采样率表（UAC2 里每个 alt 各自一套）
};

struct FormatCaps {
    bool pcm24 = false;                       // 是否有 24/32-bit PCM alt
    int pcm_subslot = 3;                      // PCM 子槽字节数（3 或 4）
    std::vector<int> pcm_dsd_rates;           // 所有 PCM alt 的速率并集（UI 展示用）
    std::vector<AltInfo> pcm_alts;            // ★ 每个 PCM alt 及其速率表
    std::vector<AltInfo> dsd_alts;            // DSD Native 的 alt（Generic Type I）
};

// 单个 AudioStreaming 接口（一个设备可能有多个：如 USB 声卡 + 麦克风）
struct StreamInfo {
    int ac_if = -1;        // AudioControl 接口号
    int as_if = -1;        // AudioStreaming（播放方向）接口号
    int clock_id = 0;
    /** ClockSource 的 bmControls 低 2 位：0=不支持频率控制，1=只读，3=读写 */
    int clock_freq_ctl = 0;
    int clock_selector_id = 0; // 若有 Clock Selector 则记录，0 = 无
    int clock_port_idx = 0;    // selector 里选中的源索引（1-based）
    FormatCaps caps;
    uint8_t ep_out = 0;    // 等时 OUT 端点地址
    uint16_t max_packet = 0;   // wMaxPacketSize 的字节数部分
    int iso_mult = 1;      // 每微帧额外事务数：0=1x 1=2x 2=3x
    int subslot = 3;       // 选中 PCM alt 的子槽
    int channels = 2;
    int pcm_alt = 0;       // 播放 PCM 时选择的 alt
    /** 当前打开流时实际使用的每帧字节数 = channels × subslot（由 open_stream 填） */
    int frame_bytes = 0;
    int dsd_alt = 0;       // 播放 DSD native 时的 alt（0 = 不支持）
    std::string describe() const;
};

// 从 Java UsbDeviceConnection.fileDescriptor 打开并解析一个设备。
// 成功返回 true，info 填充；失败把中文错误写进 err。
bool probe(int fd, int device_address, StreamInfo* info, std::string* err);

/** 按采样率挑 alt：声明支持该速率的候选里挑子槽最大的（32 > 24 > 16） */
int pick_alt(const StreamInfo& info, int rate_hz, bool want_dsd);

// claim 接口（独占开始）。alt=要激活的 alt setting。
bool open_stream(int fd, int device_address, const StreamInfo& info, int alt,
                 int rate_hz, bool dsd, std::string* err);
// 切换采样率（SET_CUR ClockFreq；DSD native 用 DSD 位率：如 DSD64 = 44100*16）
bool set_rate(int fd, int device_address, const StreamInfo& info, int rate_hz,
              bool dsd, std::string* err);
// 释放接口并归还给内核驱动（独占结束）。
void close_stream(int fd, const StreamInfo& info);

// 查询时钟当前速率（调试用）；失败返回 -1
long get_rate(int fd, int device_address, const StreamInfo& info);

} // namespace uac2
