package com.screen.vision.center

import android.util.Log

/**
 * root 触控注入：连续按住手势。
 *
 * acquire 注入 DOWN，moveBy 按绝对坐标注入 MOVE，release 注入 UP。
 * 仅在 daemon 控制通道不可用时作为回退；每事件仍会新起 su 进程。
 */
class TouchInjector : AimInjector {

    private var anchorX = 0
    private var anchorY = 0
    private var holding = false

    override fun acquire(anchorX: Int, anchorY: Int) {
        this.anchorX = anchorX
        this.anchorY = anchorY
        holding = exec("input motionevent DOWN $anchorX $anchorY")
    }

    override fun moveBy(dx: Int, dy: Int) {
        if (!holding || (dx == 0 && dy == 0)) return
        anchorX += dx
        anchorY += dy
        exec("input motionevent MOVE $anchorX $anchorY")
    }

    override fun release() {
        if (!holding) return
        holding = false
        exec("input motionevent UP $anchorX $anchorY")
    }

    private fun exec(cmd: String): Boolean {
        return try {
            Runtime.getRuntime().exec(arrayOf("su", "-c", cmd)).waitFor() == 0
        } catch (e: Exception) {
            Log.e(TAG, "Touch injection failed: ${e.message}")
            false
        }
    }

    companion object {
        private const val TAG = "TouchInjector"
    }
}
