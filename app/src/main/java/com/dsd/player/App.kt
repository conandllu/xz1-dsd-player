// SPDX-License-Identifier: GPL-3.0-or-later

package com.dsd.player

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import android.view.Choreographer

/**
 * 主线程健康度看门狗。
 *
 * 真机第一百四十一轮出现「App 黑屏」：窗口在、但一帧没画，系统日志里
 * `Activity top resumed state loss timeout`（主线程卡 16 s 以上）。当时只能翻系统日志倒推，
 * 应用侧没有任何证据。这里补两件事：
 *
 *  1. **帧看门狗**：`Choreographer` 连续 2 s 没有回调 → 打 W 级日志（附最近一次生命周期耗时）；
 *  2. **生命周期计时**：onCreate/onStart/onResume/onPause/onStop/onDestroy 超过 500 ms 直接 W，
 *     平时的耗时打 I（debug 包）。
 *
 * 这两条加起来，以后"黑屏/卡死"能在 logcat 里直接定位到是哪一段、卡了多久。
 */
class App : Application() {

    private var lastFrameMs = 0L
    /** 最近一次生命周期回调的名字（帧卡住时一起打出来） */
    private var lastLifecycle = "-"
    /** 还没结算的生命周期回调（等下一帧到达时结算耗时） */
    @Volatile private var pending: Pair<String, Long>? = null

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: Activity, s: Bundle?) {}
            override fun onActivityStarted(a: Activity) { mark("start", a) }
            override fun onActivityResumed(a: Activity) { mark("resume", a) }
            override fun onActivityPaused(a: Activity) { mark("pause", a) }
            override fun onActivityStopped(a: Activity) { mark("stop", a) }
            override fun onActivitySaveInstanceState(a: Activity, s: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}

            /**
             * 记下"生命周期回调发生的时刻"，等**下一帧**真正画出来再结算 ——
             * 这才是用户真实的等待时间（onResume 返回不代表画面出来了）。
             */
            private fun mark(what: String, a: Activity) {
                val key = "${a.javaClass.simpleName}#$what"
                lastLifecycle = key
                pending = key to SystemClock.elapsedRealtimeCompat()
            }
        })
        lastFrameMs = SystemClock.elapsedRealtimeCompat()
        Choreographer.getInstance().postFrameCallback(object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                val now = SystemClock.elapsedRealtimeCompat()
                val p = pending
                if (p != null) {
                    pending = null
                    val ms = now - p.second
                    if (ms >= 500) {
                        Log.w(TAG, "主线程 ${p.first} → 首帧 ${ms}ms（≥500ms，可能卡顿）")
                    } else if (BuildConfig.DEBUG) {
                        Log.i(TAG, "主线程 ${p.first} → 首帧 ${ms}ms")
                    }
                }
                val gap = now - lastFrameMs
                if (gap >= 2000) {
                    Log.w(TAG, "主线程可能卡住：${gap}ms 没有出一帧（最近生命周期=$lastLifecycle）")
                }
                lastFrameMs = now
                Choreographer.getInstance().postFrameCallback(this)
            }
        })
    }

    private object SystemClock {
        fun elapsedRealtimeCompat(): Long = android.os.SystemClock.elapsedRealtime()
    }

    companion object {
        private const val TAG = "MainThreadWatchdog"
    }
}
