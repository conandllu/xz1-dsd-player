// test_uac2_parse.cpp — 用真机原始描述符验证 UAC2 alt 解析（主机侧，无 libusb）
// 编译：g++ -std=c++17 -I app/src/main/cpp/native scripts/test_uac2_parse.cpp -o /tmp/test_uac2
#include "uac2_parse.h"

#include <cstdio>
#include <cstring>

static int g_fail = 0;
static void check(bool ok, const char* what) {
    printf("  %s: %s\n", ok ? "ok  " : "FAIL", what);
    if (!ok) ++g_fail;
}

int main() {
    // ── XD05 Bal 的原始 extra 块（取自 /sys/bus/usb/devices/1-1/descriptors）──
    // alt1 AS_GENERAL + FORMAT_TYPE(6 字节非标)
    const uint8_t alt1[] = {
        0x10, 0x24, 0x01, 0x02, 0x00, 0x01, 0x01, 0x00, 0x00, 0x00, 0x02, 0x00, 0x00, 0x00, 0x00, 0x0b,
        0x06, 0x24, 0x02, 0x01, 0x04, 0x20,
    };
    // alt2：bitres 24
    const uint8_t alt2[] = {
        0x10, 0x24, 0x01, 0x02, 0x00, 0x01, 0x01, 0x00, 0x00, 0x00, 0x02, 0x00, 0x00, 0x00, 0x00, 0x0b,
        0x06, 0x24, 0x02, 0x01, 0x04, 0x18,
    };
    // alt3：DSD（bmFormats=0x80000000）
    const uint8_t alt3[] = {
        0x10, 0x24, 0x01, 0x02, 0x00, 0x01, 0x00, 0x00, 0x00, 0x80, 0x02, 0x00, 0x00, 0x00, 0x00, 0x0b,
        0x06, 0x24, 0x02, 0x01, 0x04, 0x20,
    };

    printf("XD05 Bal alt1 (非标 6 字节 FORMAT_TYPE)\n");
    {
        auto a = uac2parse::parse_alt_extra(alt1, sizeof(alt1));
        check(a.fmt_type == 1, "PCM (fmt_type=1)");
        check(a.subslot == 4, "subslot=4");
        check(a.bitres == 32, "bitres=32");
        check(a.channels == 2, "channels=2 (来自 AS_GENERAL)");
        check(!a.standard_layout, "识别为非标布局");
        printf("  → frame_bytes = %d（期望 8）\n", a.channels * a.subslot);
        check(a.channels * a.subslot == 8, "帧长 8 字节");
    }

    printf("XD05 Bal alt2 (24 有效位 / 4 字节容器)\n");
    {
        auto a = uac2parse::parse_alt_extra(alt2, sizeof(alt2));
        check(a.subslot == 4, "subslot=4");
        check(a.bitres == 24, "bitres=24");
        check(a.channels * a.subslot == 8, "帧长 8 字节");
    }

    printf("XD05 Bal alt3 (DSD native)\n");
    {
        auto a = uac2parse::parse_alt_extra(alt3, sizeof(alt3));
        check(a.fmt_type == 4, "DSD (bmFormats bit31)");
        check(a.subslot == 4, "subslot=4（DSD_U32）");
        check(a.channels == 2, "channels=2");
    }

    // ── 标准 8+ 字节布局（带离散速率表）──
    printf("标准 FORMAT_TYPE_I（含速率表）\n");
    {
        const uint8_t std[] = {
            0x10, 0x24, 0x01, 0x01, 0x00, 0x01, 0x01, 0x00, 0x00, 0x00, 0x02, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x11, 0x24, 0x02, 0x01, 0x02, 0x03, 0x18, 0x03,   // bLength=17（8 头 + 3×3）
            0x44, 0xAC, 0x00,      // 44100
            0x80, 0xBB, 0x00,      // 48000
            0x00, 0x77, 0x01,      // 96000
        };
        auto a = uac2parse::parse_alt_extra(std, sizeof(std));
        check(a.standard_layout, "标准布局");
        check(a.channels == 2 && a.subslot == 3 && a.bitres == 24, "ch=2 subslot=3 bitres=24");
        check(a.rates.size() == 3, "3 个离散速率");
        check(!a.rates.empty() && a.rates[0] == 44100 && a.rates[2] == 96000, "速率值正确");
        check(a.channels * a.subslot == 6, "帧长 6 字节");
    }

    // ── 完全解析不到 FORMAT_TYPE 的退化情况 ──
    printf("无 FORMAT_TYPE（兜底）\n");
    {
        const uint8_t only_gen[] = {
            0x10, 0x24, 0x01, 0x02, 0x00, 0x01, 0x01, 0x00, 0x00, 0x00, 0x02, 0x00, 0x00, 0x00, 0x00, 0x0b,
        };
        auto a = uac2parse::parse_alt_extra(only_gen, sizeof(only_gen));
        check(a.subslot == 4, "退化时 subslot=4（不是 1）");
        check(a.channels * a.subslot == 8, "帧长仍为 8 字节");
    }

    printf(g_fail == 0 ? "\nALL PASS\n" : "\n%d FAILURES\n", g_fail);
    return g_fail == 0 ? 0 : 1;
}
