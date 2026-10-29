// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * 本地 ALAC 解码桥（Apache-2.0 的 Apple ALACDecoder，见同目录 APPLE_LICENSE.txt）。
 *
 * 为什么需要它：AOSP 默认**不带** ALAC 解码器（`c2.android.alac.decoder` 在多数
 * 镜像/ROM 里不存在），MediaCodec.createDecoderByType("audio/alac") 会直接
 * NAME_NOT_FOUND。而 iTunes Store 购买的 ALAC、自己抓轨转的 ALAC 都是无 DRM 的
 * 普通文件，完全可以在应用内解码后走 USB 独占输出。
 *
 * 输入：MediaExtractor 给出的**一帧 ALAC**（即一个 packet），
 *       以及 CSD-0 里的 magic cookie（MP4 的 'alac' atom，或裸的 24 字节配置）。
 * 输出：交织 PCM，位深 = cookie 里的 bitDepth（16/20/24/32）。
 *       ⚠ 调用方负责按 bitDepth 转换到 24-in-32（16/24 两条路径）。
 */
#include <jni.h>
#include <cstring>
#include <cstdlib>
#include <android/log.h>

#include "alac/ALACDecoder.h"
#include "alac/ALACBitUtilities.h"
#include "alac/ALACAudioTypes.h"

#define LOG_TAG "AlacJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct AlacHandle {
    ALACDecoder* decoder = nullptr;
    uint32_t frameLength = 0;
    uint32_t bitDepth = 0;
    uint32_t channels = 0;
    uint32_t sampleRate = 0;
    uint8_t* pcm = nullptr;
    size_t pcmCap = 0;
};

/** cookie 可能是 24 字节裸配置，也可能是 36 字节 'alac' atom（size+tag+version+24B） */
const uint8_t* normalize_cookie(const uint8_t* in, int len, int* outLen) {
    if (len >= 36 && memcmp(in + 4, "alac", 4) == 0) {
        *outLen = 24;
        return in + 12;              // size(4) + 'alac'(4) + version/flags(4)
    }
    if (len >= 24) { *outLen = 24; return in; }
    return nullptr;
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_dsd_player_usb_NativeBridge_nativeAlacOpen(JNIEnv* env, jobject, jbyteArray cookie) {
    if (cookie == nullptr) return 0;
    const jsize len = env->GetArrayLength(cookie);
    if (len < 24) { LOGE("alac: cookie too short (%d)", (int) len); return 0; }
    jbyte buf[256];
    const jsize n = len > 256 ? 256 : len;
    env->GetByteArrayRegion(cookie, 0, n, buf);

    int cfgLen = 0;
    const uint8_t* cfg = normalize_cookie(reinterpret_cast<const uint8_t*>(buf), n, &cfgLen);
    if (cfg == nullptr) { LOGE("alac: bad cookie"); return 0; }

    auto* h = new AlacHandle();
    h->decoder = new ALACDecoder();
    const int32_t rc = h->decoder->Init(const_cast<uint8_t*>(cfg), (uint32_t) cfgLen);
    if (rc != 0) {
        LOGE("alac: Init failed rc=%d", rc);
        delete h->decoder; delete h;
        return 0;
    }
    const ALACSpecificConfig& c = h->decoder->mConfig;
    h->frameLength = c.frameLength;
    h->bitDepth = c.bitDepth;
    h->channels = c.numChannels;
    h->sampleRate = c.sampleRate;
    const size_t need = (size_t) h->frameLength * h->channels * 4;
    h->pcm = static_cast<uint8_t*>(malloc(need));
    h->pcmCap = need;
    LOGI("alac: 解码器就绪 frameLength=%u bitDepth=%u ch=%u rate=%u",
         h->frameLength, h->bitDepth, h->channels, h->sampleRate);
    return reinterpret_cast<jlong>(h);
}

/** 返回 [frameLength, bitDepth, channels, sampleRate] */
extern "C" JNIEXPORT jintArray JNICALL
Java_com_dsd_player_usb_NativeBridge_nativeAlacInfo(JNIEnv* env, jobject, jlong handle) {
    auto* h = reinterpret_cast<AlacHandle*>(handle);
    jintArray arr = env->NewIntArray(4);
    if (h == nullptr || arr == nullptr) return arr;
    const jint v[4] = {(jint) h->frameLength, (jint) h->bitDepth,
                       (jint) h->channels, (jint) h->sampleRate};
    env->SetIntArrayRegion(arr, 0, 4, v);
    return arr;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_dsd_player_usb_NativeBridge_nativeAlacDecode(JNIEnv* env, jobject, jlong handle,
                                                     jbyteArray packet, jint packetLen,
                                                     jbyteArray out, jint outCap) {
    auto* h = reinterpret_cast<AlacHandle*>(handle);
    if (h == nullptr || packet == nullptr || out == nullptr) return -1;
    if (packetLen <= 0) return 0;
    if (packetLen > 1024 * 1024) { LOGE("alac: packet too big %d", packetLen); return -1; }

    auto* in = static_cast<uint8_t*>(malloc((size_t) packetLen));
    if (in == nullptr) return -1;
    env->GetByteArrayRegion(packet, 0, packetLen, reinterpret_cast<jbyte*>(in));

    BitBuffer bits{};
    BitBufferInit(&bits, in, (uint32_t) packetLen);
    uint32_t outNumSamples = 0;
    const int32_t rc = h->decoder->Decode(&bits, h->pcm, h->frameLength, h->channels, &outNumSamples);
    free(in);
    if (rc != 0) { LOGE("alac: Decode rc=%d", rc); return -1; }

    const uint32_t bps = h->bitDepth == 16 ? 2 : (h->bitDepth == 32 ? 4 : 3);
    const int bytes = (int) (outNumSamples * h->channels * bps);
    if (bytes > outCap) {
        LOGE("alac: 输出超过容量 %d > %d", bytes, outCap);
        return -1;
    }
    env->SetByteArrayRegion(out, 0, bytes, reinterpret_cast<const jbyte*>(h->pcm));
    return bytes;
}

extern "C" JNIEXPORT void JNICALL
Java_com_dsd_player_usb_NativeBridge_nativeAlacClose(JNIEnv*, jobject, jlong handle) {
    auto* h = reinterpret_cast<AlacHandle*>(handle);
    if (h == nullptr) return;
    if (h->decoder != nullptr) { h->decoder->~ALACDecoder(); free(h->decoder); }
    free(h->pcm);
    delete h;
}
