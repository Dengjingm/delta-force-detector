package com.screen.vision.aim

import android.util.Log
import com.screen.vision.model.DetectResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 自动瞄准控制器。
 *
 * 检测循环每帧调用 [onFrame] 更新目标（选屏幕中心最近者），
 * 独立节拍协程读取最新目标，按比例增益 + 死区 + 单步上限计算
 * 注入位移，使准星朝目标收敛。
 */
class AimController(
    private val scope: CoroutineScope,
    private val config: AimConfig = AimConfig(),
) {

    private val injector = TouchInjector()

    private data class AimTarget(
        val x: Int,
        val y: Int,
        val screenWidth: Int,
        val screenHeight: Int,
    )

    @Volatile
    private var target: AimTarget? = null

    @Volatile
    private var running = false

    /** 检测线程调用：更新最新目标（无目标传空列表）。 */
    fun onFrame(detections: List<DetectResult>, width: Int, height: Int) {
        val best = selectTarget(detections, width / 2, height / 2)
        target = best?.let { AimTarget(it.x, it.y, width, height) }
    }

    fun start() {
        if (running) return
        running = true
        scope.launch { loop() }
    }

    fun stop() {
        running = false
        target = null
    }

    private suspend fun loop() {
        while (running) {
            if (config.enabled) step()
            delay(config.aimIntervalMs)
        }
    }

    private fun step() {
        val t = target ?: return
        if (t.screenWidth <= 0 || t.screenHeight <= 0) return

        val cx = t.screenWidth / 2
        val cy = t.screenHeight / 2
        var dx = t.x - cx
        var dy = t.y - cy
        if (config.invertX) dx = -dx
        if (config.invertY) dy = -dy

        if (abs(dx) <= config.deadzonePx && abs(dy) <= config.deadzonePx) return

        val mx = (dx * config.sensitivity).toInt().coerceIn(-config.maxStepPx, config.maxStepPx)
        val my = (dy * config.sensitivity).toInt().coerceIn(-config.maxStepPx, config.maxStepPx)
        if (mx == 0 && my == 0) return

        Log.d(TAG, "aim: target=(${t.x},${t.y}) center=($cx,$cy) move=($mx,$my)")
        injector.swipe(cx, cy, cx + mx, cy + my, config.swipeDurationMs)
    }

    private fun selectTarget(
        detections: List<DetectResult>,
        cx: Int,
        cy: Int,
    ): DetectResult? = detections.minByOrNull { d ->
        val dx = (d.x - cx).toLong()
        val dy = (d.y - cy).toLong()
        dx * dx + dy * dy
    }

    companion object {
        private const val TAG = "AimController"
    }
}
