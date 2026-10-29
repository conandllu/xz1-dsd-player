// SPDX-License-Identifier: GPL-3.0-or-later
// dsd_native.cpp — DSF / DSDIFF(.dff) 解封装实现
//
// 容器事实（务必按下述规范写，别凭印象）：
//  DSF  : "DSD " + u64le chunkSize(28) + u64le fileSize + u64le metadataPtr
//         28 处 "fmt " chunk(52 字节): ver/formatId/chType/chNum/rate/bits/samples/blocksize
//         80 处 "data" chunk: 92 起为数据；数据按每声道 4096 字节块交替（L块 R块 L块…）
//         采样率字段在 fmt 内（28+28=56），不是 16；data 起点是 92，不是元数据指针
//         DSF 的比特是 MSB-first，送 DAC（LSB-first）前要逐字节位反转
//  DFF  : "FRM8" + u64be size + "DSDIFF" + 子块(id 4 + u64be size + payload)
//         PROP→"SND "→FS(4字节BE采样率)/CHNL/CMPR("DSD "未压缩，"DST "压缩)
//         音频块 id 是 "DSD "，数据紧随其后（12 字节头）；DSDIFF 原生 LSB-first，无需反转
#include "dsd_native.h"

#include <cstring>
#include <algorithm>
#include <vector>

namespace {

uint64_t rd_be64(const uint8_t* p) {
    uint64_t v = 0;
    for (int i = 0; i < 8; ++i) v = (v << 8) | p[i];
    return v;
}
uint32_t rd_be32(const uint8_t* p) {
    return (uint32_t(p[0]) << 24) | (uint32_t(p[1]) << 16) | (uint32_t(p[2]) << 8) | p[3];
}
uint16_t rd_be16(const uint8_t* p) { return (uint16_t)((p[0] << 8) | p[1]); }
uint32_t rd_le32(const uint8_t* p) {
    return uint32_t(p[0]) | (uint32_t(p[1]) << 8) | (uint32_t(p[2]) << 16) | (uint32_t(p[3]) << 24);
}
uint64_t rd_le64(const uint8_t* p) {
    uint64_t v = 0;
    for (int i = 7; i >= 0; --i) v = (v << 8) | p[i];
    return v;
}

static const uint8_t kRev[256] = {
#include "bitrev_table.h"
};
uint8_t rev8(uint8_t b) { return kRev[b]; }

constexpr int kDsfMinBlock = 4096; // DSF 每声道块大小（头部有值，异常时用默认）

// 若文件不是偶数对齐，读会失败；统一用 fseeko + fread 的辅助
bool read_at(FILE* f, long long off, uint8_t* dst, size_t n) {
    if (fseeko(f, (off_t)off, SEEK_SET) != 0) return false;
    return fread(dst, 1, n, f) == n;
}

bool parse_dsf(FILE* f, DsdFile* out, std::string* err) {
    uint8_t h[92];
    if (fseeko(f, 0, SEEK_SET) != 0 || fread(h, 1, 92, f) != 92) {
        *err = "DSF 头不完整（文件小于 92 字节）";
        return false;
    }
    // fmt 块：28 起
    if (memcmp(h + 28, "fmt ", 4) != 0) {
        *err = "DSF 缺 fmt 块";
        return false;
    }
    uint64_t fmt_size = rd_le64(h + 32);
    if (fmt_size < 52) {
        *err = "DSF fmt 块长度异常";
        return false;
    }
    uint32_t channel_type = rd_le32(h + 48);
    uint32_t channel_num = rd_le32(h + 52);
    uint32_t rate = rd_le32(h + 56);
    uint32_t bits = rd_le32(h + 60);
    uint64_t samples = rd_le64(h + 64);
    uint32_t block_size = rd_le32(h + 72);
    if (block_size < 1024 || block_size > (1u << 20)) block_size = kDsfMinBlock;
    if (rate == 0) {
        *err = "DSF 采样率为 0";
        return false;
    }
    out->rate_hz = (int)rate;
    out->channels = channel_num ? (int)channel_num : ((channel_type == 1) ? 1 : 2);
    if (channel_type == 1) out->channels = 1;
    out->dsf_block = (int)block_size;
    out->dsd_lsb_first = false;   // DSF 存储 MSB-first → 读时位反转
    out->is_dff = false;
    out->bits_per_sample = bits ? (int)bits : 1;
    out->sample_count = samples;

    // data 块：紧跟在 fmt 之后（fmt 块可能被 pad 到偶数）
    long long pos = 28 + (long long)fmt_size;
    for (int guard = 0; guard < 8; ++guard) {
        uint8_t ch[12];
        if (!read_at(f, pos, ch, 12)) break;
        uint64_t csize = rd_le64(ch + 4);
        if (memcmp(ch, "data", 4) == 0) {
            out->data_start = pos + 12;
            // DSF 规范：chunkSize 含 12 字节头 → 数据末端 = pos + chunkSize
            long long end = pos + (long long)csize;
            fseeko(f, 0, SEEK_END);
            long long fsz = (long long)ftello(f);
            if (end <= out->data_start || end > fsz) {
                // 少数写入器把 chunkSize 写成纯数据长度
                long long alt = pos + 12 + (long long)csize;
                if (alt > out->data_start && alt <= fsz) end = alt;
                else end = fsz;
                if (end <= out->data_start) {
                    *err = "DSF data 块长度为 0";
                    return false;
                }
            }
            out->data_bytes = end - out->data_start;
            return true;
        }
        if (csize == 0 || csize > (uint64_t)1 << 40) break;
        pos += 12 + (long long)csize;
    }
    *err = "DSF 未找到 data 块";
    return false;
}

bool parse_dff(FILE* f, DsdFile* out, std::string* err) {
    uint8_t head[16];
    if (fseeko(f, 0, SEEK_SET) != 0 || fread(head, 1, 16, f) != 16) {
        *err = "DFF 头不完整";
        return false;
    }
    if (memcmp(head, "FRM8", 4) != 0) {
        if (memcmp(head, "FRR8", 4) == 0) {
            // 无头裸流：按扩展名假定 DSD64 立体声，整文件为数据
            fseeko(f, 0, SEEK_END);
            out->data_start = 0;
            out->data_bytes = (long long)ftello(f);
            out->rate_hz = 2822400;
            out->channels = 2;
            out->dsd_lsb_first = true;
            out->is_dff = true;
            return true;
        }
        *err = "不是 DSDIFF（FRM8）文件";
        return false;
    }
    fseeko(f, 0, SEEK_END);
    long long fsz = (long long)ftello(f);
    uint64_t frm_size = rd_be64(head + 4);
    long long end = (long long)frm_size + 12;
    if (end <= 16 || end > fsz) end = fsz;

    out->is_dff = true;
    out->dsd_lsb_first = true;   // DSDIFF DSD 数据 LSB-first，直接送
    out->channels = 2;
    out->rate_hz = 0;
    bool compressed = false;

    // 子块扫描：FRM8 头 12 字节 + formType。规范是 4 字节 "DSD "，
    // 但个别写入器写的是字面量 "DSDIFF"(6 字节) —— 两种都容忍。
    long long pos = 16;
    {
        uint8_t ft[6] = {0};
        if (read_at(f, 12, ft, 6) && memcmp(ft, "DSDIFF", 6) == 0) pos = 18;
    }
    int guard = 0;
    while (pos + 12 <= end && ++guard < 512) {
        uint8_t ch[12];
        if (!read_at(f, pos, ch, 12)) break;
        uint64_t csize = rd_be64(ch + 4);
        if (csize > (uint64_t)1 << 40) break;
        long long body = pos + 12;

        if (memcmp(ch, "PROP", 4) == 0) {
            // PROP + "SND " + 子块（FS / CHNL / CMPR ...）
            long long sub = body + 4;
            long long propEnd = body + (long long)csize;
            if (propEnd > end) propEnd = end;
            while (sub + 12 <= propEnd) {
                uint8_t sc[12];
                if (!read_at(f, sub, sc, 12)) break;
                uint64_t ssize = rd_be64(sc + 4);
                long long sbody = sub + 12;
                if (memcmp(sc, "FS  ", 4) == 0 && ssize >= 4) {
                    uint8_t v[4];
                    if (read_at(f, sbody, v, 4)) out->rate_hz = (int)rd_be32(v);
                } else if (memcmp(sc, "CHNL", 4) == 0 && ssize >= 2) {
                    uint8_t v[2];
                    if (read_at(f, sbody, v, 2)) {
                        int n = rd_be16(v);
                        if (n > 0 && n <= 8) out->channels = n;
                    }
                } else if (memcmp(sc, "CMPR", 4) == 0 && ssize >= 4) {
                    uint8_t v[4];
                    if (read_at(f, sbody, v, 4) && memcmp(v, "DST ", 4) == 0) compressed = true;
                }
                if (ssize == 0) break;
                sub += 12 + (long long)ssize + (long long)(ssize & 1);
            }
        } else if (memcmp(ch, "DSD ", 4) == 0) {
            out->data_start = body;
            long long dEnd = body + (long long)csize;
            if (dEnd > end) dEnd = end;
            out->data_bytes = dEnd - out->data_start;
            break;
        }
        if (csize == 0) { pos += 12; continue; }
        pos += 12 + (long long)csize + (long long)(csize & 1);
    }

    if (compressed) {
        *err = "DFF 使用 DST 压缩，请先转码（ffmpeg -i in.dff -c:a flac out.flac 或转 DSF）";
        return false;
    }
    if (out->data_bytes <= 0) {
        *err = "DFF 未找到 DSD 音频块";
        return false;
    }
    if (out->rate_hz == 0) out->rate_hz = 2822400; // 部分文件缺 FS，按 DSD64 兜底
    return true;
}

} // namespace

bool dsd_open(const char* path, DsdFile* out, std::string* err) {
    FILE* f = fopen(path, "rb");
    if (!f) {
        *err = "无法打开文件";
        return false;
    }
    if (!dsd_open_at(f, out, err)) return false;
    return true;
}

bool dsd_open_at(FILE* file, DsdFile* out, std::string* err) {
    memset(out, 0, sizeof(*out));
    out->f = file;
    uint8_t magic[4];
    if (fseeko(file, 0, SEEK_SET) != 0 || fread(magic, 1, 4, file) != 4) {
        *err = "文件太短";
        dsd_close(out);
        return false;
    }
    bool ok;
    if (memcmp(magic, "DSD ", 4) == 0) {
        ok = parse_dsf(file, out, err);       // DSF 以 "DSD " 开头
    } else if (memcmp(magic, "FRM8", 4) == 0 || memcmp(magic, "FRR8", 4) == 0) {
        ok = parse_dff(file, out, err);       // DSDIFF
    } else {
        *err = "不是 DSF/DSDIFF 文件（魔数不匹配）";
        ok = false;
    }
    if (!ok) {
        dsd_close(out);
        return false;
    }

    if (out->rate_hz <= 0) {
        *err = "无法确定 DSD 位率";
        dsd_close(out);
        return false;
    }
    switch (out->rate_hz) {
        case 2822400: out->dsd_mode = 64; break;
        case 5644800: out->dsd_mode = 128; break;
        case 11289600: out->dsd_mode = 256; break;
        case 22579200: out->dsd_mode = 512; break;
        default: out->dsd_mode = out->rate_hz / 44100; break;
    }
    // 每声道字节数（立体声时 data_bytes/2）
    if (out->channels <= 0) out->channels = 2;
    return true;
}

int dsd_read(DsdFile* f, long long pos, uint8_t* dst, int count) {
    if (!f->f) return -1;
    if (count <= 0) return 0;
    if (f->is_dff) {
        if (fseeko(f->f, (off_t)(f->data_start + pos), SEEK_SET) != 0) return -1;
        size_t got = fread(dst, 1, (size_t)count, f->f);
        return (int)got;                      // LSB-first，原样输出
    }
    // DSF：每声道按 block 字节分块交替 [L块][R块][L块]…
    // pos 是交织流位置：每 2 字节 = 1 帧（L,R 各 1 字节），第 i 帧的每声道字节序号 = pos/2
    const int block = f->dsf_block > 0 ? f->dsf_block : kDsfMinBlock;
    thread_local std::vector<uint8_t> lbuf, rbuf;
    int frames = count / 2;
    if (frames <= 0) return 0;
    lbuf.resize(frames);
    rbuf.resize(frames);

    long long p = pos / 2;                    // 每声道字节位置
    const long long totalPerCh = f->data_bytes / (f->channels > 0 ? f->channels : 2);
    int done = 0;                             // 已产生的帧数
    while (done < frames && p < totalPerCh) {
        long long blk = p / block;
        int off = (int)(p % block);
        // 末块可能不满：该块实际长度 = min(block, totalPerCh - blk*block)
        long long blockFrames = std::min<long long>(block, totalPerCh - blk * block);
        int n = (int)std::min<long long>(frames - done, blockFrames - off);
        if (n <= 0) break;
        long long lOff = f->data_start + blk * (long long)block * f->channels + off;
        long long rOff = lOff + blockFrames;  // 同组内 R 块紧随 L 块（末块按实际长度）
        if (!read_at(f->f, lOff, lbuf.data() + done, (size_t)n)) break;
        if (f->channels >= 2) {
            if (!read_at(f->f, rOff, rbuf.data() + done, (size_t)n)) break;
        } else {
            memcpy(rbuf.data() + done, lbuf.data() + done, (size_t)n);
        }
        done += n;
        p += n;
    }
    for (int i = 0; i < done; ++i) {
        dst[2 * i] = rev8(lbuf[i]);           // DSF MSB-first → LSB-first
        dst[2 * i + 1] = rev8(rbuf[i]);
    }
    return done * 2;
}

void dsd_close(DsdFile* f) {
    if (f->f) fclose(f->f);
    memset(f, 0, sizeof(*f));
}

bool dsd_seek(DsdFile* f, long long byte_pos) {
    if (!f->f) return false;
    if (f->is_dff) return fseeko(f->f, (off_t)(f->data_start + byte_pos), SEEK_SET) == 0;
    return true; // DSF 读是位置无关的（dsd_read 自带定位）
}
