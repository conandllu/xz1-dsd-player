// SPDX-License-Identifier: GPL-3.0-or-later
// jni_bridge.cpp — Kotlin <-> native 桥
#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <string>
#include <vector>
#include <unistd.h>

#include "uac2_raw.h"
#include "usb_stream.h"
#include "dsd_native.h"

#define LOG_TAG "dsdplayer.jni"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using namespace uac2;

namespace {
StreamInfo g_info;
int g_fd = -1;
int g_devaddr = 0;
int g_frame_bytes = 0;
std::string g_last_err;
DsdFile g_dsd;
}

static jstring jstr(JNIEnv* env, const std::string& s) {
    return env->NewStringUTF(s.c_str());
}

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_dsd_player_usb_NativeBridge_nativeLastError(JNIEnv* env, jobject) {
    return jstr(env, g_last_err);
}

// 探测设备能力，返回字符串描述；失败返回 null（看 lastError）
JNIEXPORT jstring JNICALL
Java_com_dsd_player_usb_NativeBridge_nativeProbe(JNIEnv* env, jobject, jint fd, jint addr) {
    g_fd = fd;
    g_devaddr = addr;
    std::string err;
    if (!probe(fd, addr, &g_info, &err)) {
        g_last_err = err;
        return nullptr;
    }
    g_last_err.clear();
    return jstr(env, g_info.describe());
}

JNIEXPORT jboolean JNICALL
Java_com_dsd_player_usb_NativeBridge_nativeSupportsDsdNative(JNIEnv* env, jobject) {
    return g_info.dsd_alt != 0 ? JNI_TRUE : JNI_FALSE;
}

// 打开流：mode 0=PCM, 1=DSD native, 2=DoP；rate = Hz（DSD 传位率）
JNIEXPORT jboolean JNICALL
Java_com_dsd_player_usb_NativeBridge_nativeOpenStream(JNIEnv* env, jobject, jint mode, jint rate) {
    std::string err;
    bool want_dsd = (mode == 1);
    int alt = pick_alt(g_info, rate, want_dsd);
    if (alt == 0) {
        g_last_err = want_dsd ? "设备不支持 DSD Native" : "未找到 PCM 播放 alt";
        return JNI_FALSE;
    }
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "nativeOpenStream: mode=%d rate=%d -> alt=%d",
                        mode, rate, alt);
    if (!open_stream(g_fd, g_devaddr, g_info, alt, rate, want_dsd, &err)) {
        g_last_err = err;
        return JNI_FALSE;
    }
    // 记录实际帧长：channels × subslot。Java 侧据此决定打包字节数（不要硬编码 8）
    g_frame_bytes = g_info.channels * (g_info.subslot > 0 ? g_info.subslot : 4);
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG,
                        "open: alt=%d ch=%d subslot=%d -> frame_bytes=%d (dsd=%d)",
                        alt, g_info.channels, g_info.subslot, g_frame_bytes, (int)want_dsd);
    g_last_err.clear();
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_dsd_player_usb_NativeBridge_nativeCloseStream(JNIEnv* env, jobject) {
    usbaudio::stop();
    close_stream(g_fd, g_info);
}

JNIEXPORT jint JNICALL
Java_com_dsd_player_usb_NativeBridge_nativeGetFrameBytes(JNIEnv*, jobject) {
    return g_frame_bytes;
}

JNIEXPORT jint JNICALL
Java_com_dsd_player_usb_NativeBridge_nativeGetRate(JNIEnv* env, jobject) {
    return (jint)get_rate(g_fd, g_devaddr, g_info);
}

// 启动 ISO 泵；frame_bytes = 每帧字节（PCM24=8，DSD native=2，DoP=8）
JNIEXPORT jboolean JNICALL
Java_com_dsd_player_usb_NativeBridge_nativeStartPump(JNIEnv* env, jobject, jint rate,
                                                     jint frame_bytes, jint mode) {
    std::string err;
    // mode：0=PCM 1=DSD native 2=DoP（DoP 的载荷是 DSD，不能乘增益）
    bool dsd = (mode == 1);
    bool dop = (mode == 2);
    if (!usbaudio::start(g_fd, g_info, rate, frame_bytes, dsd, dop, &err)) {
        g_last_err = err;
        return JNI_FALSE;
    }
    return JNI_TRUE;
}
JNIEXPORT void JNICALL
Java_com_dsd_player_usb_NativeBridge_nativeStopPump(JNIEnv*, jobject) { usbaudio::stop(); }
JNIEXPORT void JNICALL
Java_com_dsd_player_usb_NativeBridge_nativeSetMute(JNIEnv*, jobject, jboolean mute) {
    usbaudio::set_mute(mute == JNI_TRUE);
}

JNIEXPORT jboolean JNICALL
Java_com_dsd_player_usb_NativeBridge_nativePumpRunning(JNIEnv*, jobject) {
    return usbaudio::is_running() ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT void JNICALL
Java_com_dsd_player_usb_NativeBridge_nativeRingReset(JNIEnv*, jobject) { usbaudio::ring_reset(); }
JNIEXPORT jint JNICALL
Java_com_dsd_player_usb_NativeBridge_nativeRingFree(JNIEnv*, jobject) { return usbaudio::ring_free(); }
JNIEXPORT jint JNICALL
Java_com_dsd_player_usb_NativeBridge_nativeRingUsed(JNIEnv*, jobject) { return usbaudio::ring_used(); }

JNIEXPORT void JNICALL
Java_com_dsd_player_usb_NativeBridge_nativeRingDiscard(JNIEnv*, jobject) {
    usbaudio::ring_discard();
}

JNIEXPORT void JNICALL
Java_com_dsd_player_usb_NativeBridge_nativeSeekFlush(JNIEnv*, jobject) {
    usbaudio::seek_flush();
}

JNIEXPORT jboolean JNICALL
Java_com_dsd_player_usb_NativeBridge_nativeSeekFlushBlocking(JNIEnv*, jobject, jint timeoutMs) {
    return usbaudio::seek_flush_blocking(timeoutMs) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_dsd_player_usb_NativeBridge_nativeRingWrite(JNIEnv* env, jobject, jbyteArray data,
                                                     jint off, jint len) {
    std::vector<jbyte> tmp;
    jbyte* p;
    if (off == 0 && len <= 4096) {
        p = env->GetByteArrayElements(data, nullptr);
        int n = usbaudio::ring_write(reinterpret_cast<const uint8_t*>(p), len);
        env->ReleaseByteArrayElements(data, p, JNI_ABORT);
        return n;
    }
    tmp.resize(len);
    env->GetByteArrayRegion(data, off, len, tmp.data());
    return usbaudio::ring_write(reinterpret_cast<const uint8_t*>(tmp.data()), len);
}

JNIEXPORT jlongArray JNICALL
Java_com_dsd_player_usb_NativeBridge_nativeStats(JNIEnv* env, jobject) {
    auto s = usbaudio::stats();
    jlong v[4] = {s.submitted, s.bytes, s.underruns, s.errors};
    jlongArray a = env->NewLongArray(4);
    env->SetLongArrayRegion(a, 0, 4, v);
    return a;
}

// ── DSD 容器 ────────────────────────────────────────────────────────────
JNIEXPORT jboolean JNICALL
Java_com_dsd_player_usb_NativeBridge_nativeDsdOpen(JNIEnv* env, jobject, jstring path) {
    const char* p = env->GetStringUTFChars(path, nullptr);
    std::string err;
    bool ok = dsd_open(p, &g_dsd, &err);
    env->ReleaseStringUTFChars(path, p);
    if (!ok) {
        g_last_err = err;
        return JNI_FALSE;
    }
    g_last_err.clear();
    return JNI_TRUE;
}
JNIEXPORT jboolean JNICALL
Java_com_dsd_player_usb_NativeBridge_nativeDsdOpenFd(JNIEnv* env, jobject, jint fd,
                                                     jlong offset) {
    FILE* f = fdopen(dup(fd), "rb");
    if (!f) { g_last_err = "dup(fd) 失败"; return JNI_FALSE; }
    fseeko(f, (off_t)offset, SEEK_SET);
    std::string err;
    if (!dsd_open_at(f, &g_dsd, &err)) { g_last_err = err; return JNI_FALSE; }
    g_last_err.clear();
    return JNI_TRUE;
}
JNIEXPORT void JNICALL
Java_com_dsd_player_usb_NativeBridge_nativeDsdClose(JNIEnv*, jobject) { dsd_close(&g_dsd); }
JNIEXPORT jlong JNICALL
Java_com_dsd_player_usb_NativeBridge_nativeDsdTotalBytes(JNIEnv*, jobject) {
    return (jlong)g_dsd.data_bytes;
}
JNIEXPORT jint JNICALL
Java_com_dsd_player_usb_NativeBridge_nativeDsdRate(JNIEnv*, jobject) { return g_dsd.rate_hz; }
JNIEXPORT jboolean JNICALL
Java_com_dsd_player_usb_NativeBridge_nativeDsdLsbFirst(JNIEnv*, jobject) {
    return g_dsd.dsd_lsb_first ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT jint JNICALL
Java_com_dsd_player_usb_NativeBridge_nativeDsdRead(JNIEnv* env, jobject, jlong pos,
                                                   jbyteArray dst, jint off, jint len) {
    std::vector<uint8_t> tmp(len);
    int n = dsd_read(&g_dsd, (long long)pos, tmp.data(), len);
    if (n > 0) env->SetByteArrayRegion(dst, off, n, reinterpret_cast<jbyte*>(tmp.data()));
    return n;
}
} // extern "C"
