// SPDX-License-Identifier: GPL-3.0-or-later
// uac2_parse.h — UAC2 AudioStreaming alt 描述符解析（纯函数，无 libusb 依赖，可在主机上单测）
//
// 支持两种 FORMAT_TYPE 布局：
//   标准 8+ 字节： [3]=bFormatType [4]=bNrChannels [5]=bSubframeSize [6]=bBitResolution
//                  [7]=bSamFreqType [8..]=tSamFreq
//   非标 6 字节：  [3]=bFormatType [4]=bSubframeSize [5]=bBitResolution
//                  （XD05 Bal 这种；通道数从 AS_GENERAL 的 [10] 取）
#pragma once
#include <cstdint>
#include <vector>
#include <string>

namespace uac2parse {

constexpr uint8_t kCsInterface = 0x24;
constexpr uint8_t kAsGeneral = 0x01;
constexpr uint8_t kAsFormatTypeDesc = 0x02;

struct AltParsed {
    int fmt_type = 0;          // 1 = FORMAT_TYPE_I(PCM)，4 = DSD native
    int subslot = 0;           // 每声道字节数
    int bitres = 0;            // 有效位宽
    int channels = 0;          // 来自 AS_GENERAL
    uint32_t bm_formats = 0;   // AS_GENERAL 的 bmFormats（bit31 = DSD）
    std::vector<int> rates;    // FORMAT_TYPE 尾部 tSamFreq
    bool standard_layout = false;
};

inline int rd24(const uint8_t* b) {
    return (int)b[0] | ((int)b[1] << 8) | ((int)b[2] << 16);
}

/** 解析一个 alt 的 class-specific extra 描述符块 */
inline AltParsed parse_alt_extra(const uint8_t* extra, int extra_len) {
    AltParsed out;
    const uint8_t* p = extra;
    const uint8_t* end = extra + extra_len;
    while (p + 2 <= end && p[0] >= 2 && p + p[0] <= end) {
        if (p[1] == kCsInterface) {
            if (p[2] == kAsGeneral && p[0] >= 11) {
                out.bm_formats = (uint32_t)p[6] | ((uint32_t)p[7] << 8) |
                                 ((uint32_t)p[8] << 16) | ((uint32_t)p[9] << 24);
                if (p[10] > 0) out.channels = p[10];
            } else if (p[2] == kAsFormatTypeDesc && p[0] >= 6) {
                if (p[0] >= 8) {
                    out.standard_layout = true;
                    if (p[4] > 0 && out.channels == 0) out.channels = p[4];
                    out.subslot = p[5];
                    out.bitres = p[6];
                } else {                       // 6 字节非标布局
                    out.subslot = p[4];
                    out.bitres = p[5];
                }
                if (p[3] == 0x01) out.fmt_type = 1;
                else if (p[3] == 0x04) out.fmt_type = 4;

                // 采样率表（仅标准布局且带 bSamFreqType 时存在）
                if (p[0] >= 9) {
                    int freq_type = p[7];
                    const uint8_t* rq = p + 8;
                    const uint8_t* rend = p + p[0];
                    if (freq_type == 0) {
                        if (rq + 9 <= rend) {
                            int mn = rd24(rq), mx = rd24(rq + 3), st = rd24(rq + 6);
                            if (st <= 0) st = 1;
                            for (long v = mn; v <= mx && out.rates.size() < 64; v += st)
                                out.rates.push_back((int)v);
                        }
                    } else {
                        for (int n = 0; n < freq_type && rq + 3 <= rend && out.rates.size() < 64; ++n) {
                            int v = rd24(rq);
                            if (v > 0) out.rates.push_back(v);
                            rq += 3;
                        }
                    }
                }
            }
        }
        p += p[0];
    }
    // bmFormats bit31 = DSD（部分设备 FORMAT_TYPE 仍写 Type I）
    if (out.bm_formats & 0x80000000u) out.fmt_type = 4;
    // 兜底：解析不到子槽时按 UAC2 最常见的 4 字节/声道，绝不退化成 1 字节
    if (out.subslot <= 0) out.subslot = 4;
    if (out.bitres <= 0) out.bitres = out.subslot * 8;
    if (out.channels <= 0) out.channels = 2;
    return out;
}

} // namespace uac2parse
