// SPDX-License-Identifier: GPL-3.0-or-later
// dsd_native.h — DSF / DSDIFF(.dff) 解封装 → UAC2 DSD Native 字节流
//
// 输出格式：每帧 = 2 字节（L 一字节 + R 一字节），字节内位序由 dsd_lsb_first
// 决定（DSF 存储 MSB-first，读出时位反转为 LSB-first；DSDIFF 原生 LSB-first）。
// UAC2 的 DSD 传输格式定义就是"每声道 1 字节"，native 泵按 frame_bytes=2 搬运。
#pragma once
#include <cstdint>
#include <cstdio>
#include <string>

struct DsdFile {
    FILE* f = nullptr;
    long long data_start = 0;   // DSD 数据首字节的文件偏移
    long long data_bytes = 0;   // DSD 数据总字节数（立体声交织后）
    int channels = 2;
    int rate_hz = 0;            // DSD 位率：DSD64=2822400
    int dsd_mode = 0;           // 64/128/256/512（供 UI）
    int dsf_block = 4096;       // DSF 每声道块大小（DSF 有效）
    int bits_per_sample = 1;    // DSF fmt 里的 bits（1 或 8）
    long long sample_count = 0; // DSF fmt 里的每声道样本数（DSD 位计数）
    bool dsd_lsb_first = false;
    bool is_dff = false;
    char title[256] = {0};
    char artist[256] = {0};
};

// 打开并解析容器头。成功返回 true；失败 err 填中文原因。
bool dsd_open(const char* path, DsdFile* out, std::string* err);
// 从已定位的 FILE* 打开（自己负责 fseek 到容器起点）
bool dsd_open_at(FILE* f, DsdFile* out, std::string* err);
// 读 count 字节交织 DSD 数据（pos = 交织流位置）。
// DSF：读出后做字节位反转 → MSB-first 归一；DFF：原样返回（已 LSB-first）。
int dsd_read(DsdFile* f, long long pos, uint8_t* dst, int count);
void dsd_close(DsdFile* f);
bool dsd_seek(DsdFile* f, long long byte_pos); // byte_pos 相对 data_start
