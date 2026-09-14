package com.screen.vision.aim

import android.util.Log

/**
 * root 触控注入。通过 `su -c input swipe` 注入一段拖拽，
 * 用于将准星/视角朝目标方向移动。
 *
 * 每次调用会新起一个 su 进程；`input swipe` 的 duration 控制移动平滑度。
 * 低延迟的 sendevent/持久 su 会话属于后续优化，不在首个闭环内。
 */
class TouchInjector {

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean {
        return try {
            val cmd = "input swipe $x1 $y1 $x2 $y2 $durationMs"
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            p.waitFor() == 0
        } catch (e: Exception) {
            Log.e(TAG, "Touch injection failed: ${e.message}")
            false
        }
    }

    companion object {
        private const val TAG = "TouchInjector"
    }
}
