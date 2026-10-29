// test_dsd_host.cpp — 主机侧验证 dsd_native.cpp（不依赖 Android）
// 编译：g++ -std=c++17 -I app/src/main/cpp/native \
//          scripts/test_dsd_host.cpp app/src/main/cpp/native/dsd_native.cpp -o /tmp/test_dsd
#include "dsd_native.h"

#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

static int g_fail = 0;
static void check(bool cond, const char* what) {
    if (!cond) { printf("  FAIL: %s\n", what); ++g_fail; }
    else printf("  ok  : %s\n", what);
}

int main(int argc, char** argv) {
    if (argc < 3) { printf("usage: %s <dsf64.dsf> <dff64.dff> [dsf_meta.dsf]\n", argv[0]); return 2; }
    const int kRate = 2822400, kFrames = 20000;

    // 期望数据：L[i]=(i*7+3)&0xFF, R[i]=(i*11+5)&0xFF；DSF 读出后位反转
    auto bitrev = [](uint8_t b) {
        uint8_t r = 0;
        for (int i = 0; i < 8; ++i) r = (uint8_t)((r << 1) | ((b >> i) & 1));
        return r;
    };
    std::vector<uint8_t> L(kFrames), R(kFrames);
    for (int i = 0; i < kFrames; ++i) {
        L[i] = (uint8_t)((i * 7 + 3) & 0xFF);
        R[i] = (uint8_t)((i * 11 + 5) & 0xFF);
    }

    // ── DSF ──
    {
        DsdFile f{};
        std::string err;
        printf("DSF: %s\n", argv[1]);
        bool ok1 = dsd_open(argv[1], &f, &err);
        if (!ok1) printf("  err=%s\n", err.c_str());
        check(ok1, "open");
        if (f.f) {
            check(f.rate_hz == kRate, "rate == 2822400");
            check(!f.is_dff, "is_dsf");
            check(f.channels == 2, "channels == 2");
            check(f.data_bytes == (long long)kFrames * 2, "data_bytes == frames*2");
            check(!f.dsd_lsb_first, "dsf needs bitrev");
            check(f.dsf_block == 4096, "block == 4096");

            // 整段读
            std::vector<uint8_t> got(kFrames * 2);
            int n = dsd_read(&f, 0, got.data(), (int)got.size());
            check(n == (int)got.size(), "read full length");
            bool l_ok = true, r_ok = true;
            for (int i = 0; i < kFrames; ++i) {
                if (got[2 * i] != bitrev(L[i])) { l_ok = false; break; }
                if (got[2 * i + 1] != bitrev(R[i])) { r_ok = false; break; }
            }
            check(l_ok, "left channel bitrev correct (across 4096 blocks)");
            check(r_ok, "right channel bitrev correct (across 4096 blocks)");

            // 跨块边界的小块读（模拟 Producer 任意 chunk）
            std::vector<uint8_t> seg(2 * 100);
            long long startFrame = 4096 - 37;
            int m = dsd_read(&f, startFrame * 2, seg.data(), (int)seg.size());
            check(m == (int)seg.size(), "cross-block read length");
            bool seg_ok = true;
            for (int i = 0; i < 100; ++i) {
                if (seg[2 * i] != bitrev(L[startFrame + i]) ||
                    seg[2 * i + 1] != bitrev(R[startFrame + i])) { seg_ok = false; break; }
            }
            check(seg_ok, "cross-block read content");

            // 奇数对齐位置的读（模拟 seek 到非块边界）
            std::vector<uint8_t> seg2(2 * 64);
            long long sf2 = 8192 + 13;
            int m2 = dsd_read(&f, sf2 * 2, seg2.data(), (int)seg2.size());
            check(m2 == (int)seg2.size(), "unaligned read length");
            bool seg2_ok = true;
            for (int i = 0; i < 64; ++i)
                if (seg2[2 * i] != bitrev(L[sf2 + i])) { seg2_ok = false; break; }
            check(seg2_ok, "unaligned read content");

            // 尾部截断读
            std::vector<uint8_t> tail(2 * 8);
            int t = dsd_read(&f, (long long)(kFrames - 4) * 2, tail.data(), (int)tail.size());
            check(t == 8, "tail read clamped to data end (4 frames left)");
            dsd_close(&f);
        }
    }

    // ── DSF with metadata pointer (data 仍在 92) ──
    if (argc >= 4) {
        DsdFile f{};
        std::string err;
        printf("DSF(meta): %s\n", argv[3]);
        check(dsd_open(argv[3], &f, &err), "open with metadata ptr");
        if (f.f) {
            check(f.rate_hz == kRate, "rate");
            std::vector<uint8_t> got(2 * 16);
            int n = dsd_read(&f, 0, got.data(), 32);
            check(n == 32 && got[0] == bitrev(L[0]), "first sample correct");
            dsd_close(&f);
        }
    }

    // ── DFF ──
    {
        DsdFile f{};
        std::string err;
        printf("DFF: %s\n", argv[2]);
        bool ok2 = dsd_open(argv[2], &f, &err);
        if (!ok2) printf("  err=%s\n", err.c_str());
        check(ok2, "open");
        if (f.f) {
            check(f.is_dff, "is_dff");
            check(f.rate_hz == kRate, "rate == 2822400");
            check(f.channels == 2, "channels == 2");
            check(f.dsd_lsb_first, "dff lsb-first passthrough");
            check(f.data_bytes == (long long)kFrames * 2, "data_bytes == frames*2");
            std::vector<uint8_t> got(kFrames * 2);
            int n = dsd_read(&f, 0, got.data(), (int)got.size());
            check(n == (int)got.size(), "read full length");
            bool ok = true;
            for (int i = 0; i < kFrames; ++i)
                if (got[2 * i] != L[i] || got[2 * i + 1] != R[i]) { ok = false; break; }
            check(ok, "interleaved content passthrough");
            dsd_close(&f);
        }
    }

    printf(g_fail == 0 ? "\nALL PASS\n" : "\n%d FAILURES\n", g_fail);
    return g_fail == 0 ? 0 : 1;
}
