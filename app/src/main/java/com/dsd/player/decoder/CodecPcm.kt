// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsd.player.decoder

import android.media.AudioFormat
import android.media.MediaFormat
import android.util.Log

/**
 * 解码器（MediaCodec）输出 PCM 的位深读法与「统一转 24-in-32」的判据。
 *
 * 为什么需要这个类：USB 端帧长固定 8B（2ch × 4B，24-in-32）。CODEC 分支若不知
 * 道解码器实际输出的是 16bit 还是 32bit，就会把 16bit 数据当 32bit 直通写出去，
 * 一个 8B 帧只消耗半个立体声帧 → 数据消耗速度翻倍 → **2 倍速、音调高一个八度**
 * （第九十七轮真机现象：m4a/AAC「声音尖、像外星人」）。
 *
 * 读 key 的坑：AOSP codec / codec2 输出的 MediaFormat 里，位深可能写在
 * `pcm-encoding`，也可能只写在 codec2 的内部 key `android._config-pcm-encoding`
 * （真机日志里只出现后者，前者读到 0）。所以两个 key 都要看，取第一个有效值；
 * 都读不到时按 Android 解码器默认的 16bit 处理（绝不能默认 32bit）。
 */
internal object CodecPcm {

    private const val TAG = "CodecPcm"

    /** 解码器输出格式里可能出现的位深 key，按优先级 */
    private val KEYS = arrayOf("pcm-encoding", "android._config-pcm-encoding")

    /** 读解码器输出位深；返回 0 = 两个 key 都没有/无效 */
    fun read(format: MediaFormat?): Int {
        if (format == null) return 0
        for (k in KEYS) {
            if (!runCatching { format.containsKey(k) }.getOrDefault(false)) continue
            val v = runCatching { format.getInteger(k) }.getOrNull() ?: continue
            if (v > 0) return v
        }
        return 0
    }

    /** 有效语：把 0/未知收敛成 16bit（Android 解码器默认输出） */
    fun effective(enc: Int): Int =
        if (enc == AudioFormat.ENCODING_PCM_16BIT ||
            enc == AudioFormat.ENCODING_PCM_FLOAT ||
            enc == AudioFormat.ENCODING_PCM_24BIT_PACKED ||
            enc == AudioFormat.ENCODING_PCM_32BIT
        ) enc else AudioFormat.ENCODING_PCM_16BIT

    /** 每样本字节数：16bit=2、24bit packed=3、32bit/float=4 */
    fun bytesPerSample(enc: Int): Int = when (effective(enc)) {
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
        AudioFormat.ENCODING_PCM_32BIT, AudioFormat.ENCODING_PCM_FLOAT -> 4
        else -> 2
    }

    /** 是否浮点输出（4=ENCODING_PCM_FLOAT） */
    fun isFloat(enc: Int): Boolean = effective(enc) == AudioFormat.ENCODING_PCM_FLOAT

    fun describe(enc: Int): String = when (enc) {
        AudioFormat.ENCODING_PCM_16BIT -> "2(16bit)"
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> "21(24bit packed)"
        AudioFormat.ENCODING_PCM_32BIT -> "22(32bit)"
        AudioFormat.ENCODING_PCM_FLOAT -> "4(float)"
        0 -> "0(未设置)"
        else -> "$enc(未知)"
    }

    /**
     * 自检：解码器输出的字节数应当与它自己报的 pts 成正比。
     *
     * 每次产出后累加 `cumBytes` 并与 `ptsUs × rate × 8 / 1e6`（按 24-in-32 立体声
     * 应有的字节数）比对；比例落在 0.7~1.4 之外说明位深判断错了（典型：16bit 被
     * 当 32bit → 比例 ≈ 2.0）。每源只报一次，便于真机不用耳朵听就能定位。
     */
    class SelfCheck(private val tag: String, private val rate: Int, private val frameBytes: Int) {
        private var cumBytes = 0L
        private var basePts = Long.MIN_VALUE
        private var cumBytesAtBase = 0L
        private var done = false

        fun onFormat(enc: Int) {
            Log.i(tag, "解码输出 encoding=${describe(enc)} → 转 24in32（每帧 ${frameBytes}B）")
        }

        fun feed(produced: Int, ptsUs: Long) {
            if (produced <= 0) return
            cumBytes += produced
            if (basePts == Long.MIN_VALUE) {
                if (ptsUs < 0) return
                basePts = ptsUs
                cumBytesAtBase = cumBytes - produced
                return
            }
            if (done || ptsUs < 0 || rate <= 0) return
            val dUs = ptsUs - basePts
            // 攒够 ~2 秒再判，避免首缓冲/priming 误差
            if (dUs < 2_000_000L) return
            val dBytes = cumBytes - cumBytesAtBase
            val expect = dUs * rate * frameBytes / 1_000_000L
            if (expect <= 0) return
            done = true
            val ratio = dBytes.toDouble() / expect
            val msg = "解码自检：$dBytes B / ${dUs / 1000} ms → 比例 " +
                String.format("%.2f", ratio) + "（应 ≈1.00）"
            if (ratio in 0.7..1.4) Log.i(tag, "$msg ✅")
            else Log.w(tag, "$msg ⚠ 位深不匹配，实际输出速度=${String.format("%.2f", ratio)}×")
        }
    }
}
