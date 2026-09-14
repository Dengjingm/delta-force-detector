package com.screen.vision.center

import com.screen.vision.model.DetectResult
import kotlin.math.abs

/**
 * 屏幕中心移动控制器：粘滞锁定 + 每检测帧一次修正。
 *
 * 无锁定时选离屏幕中心最近的目标并按下（acquire），此后每帧用中心距离
 * 关联同一目标持续跟踪；连续 [ScreenCenterConfig.releaseAfterMisses] 帧未匹配
 * 或停用时抬起（release）。每帧修正量经 死区 + 比例增益 + 单步上限 限幅后
 * 以相对位移注入，使屏幕中心朝目标收敛。
 *
 * [config] 为 @Volatile, 每帧顶部原子读取一次供整帧使用, 支持运行中热更新。
 */
class ScreenCenterController(
    private val injector: AimInjector = TouchInjector(),
    config: ScreenCenterConfig = ScreenCenterConfig(),
) {

    private data class LockedTarget(val x: Int, val y: Int, val missCount: Int)

    @Volatile
    var config: ScreenCenterConfig = config
        private set

    fun updateConfig(newConfig: ScreenCenterConfig) {
        config = newConfig
    }

    private var target: LockedTarget? = null

    /** 检测线程每帧调用；无目标传空列表表示本帧未匹配。 */
    fun onFrame(detections: List<DetectResult>, width: Int, height: Int) {
        val cfg = config
        if (!cfg.enabled || width <= 0 || height <= 0) {
            release()
            return
        }
        val cx = width / 2
        val cy = height / 2

        val current = target
        if (current == null) {
            val acquired = nearestTo(detections, cx, cy) ?: return
            injector.acquire(cx, cy)
            target = LockedTarget(acquired.x, acquired.y, 0)
            correct(acquired.x, acquired.y, cx, cy, cfg)
            return
        }

        val matched = nearestTo(detections, current.x, current.y)
        val thresholdSq = cfg.associateDistancePx.toLong() * cfg.associateDistancePx
        if (matched != null && distSq(matched.x, matched.y, current.x, current.y) <= thresholdSq) {
            target = LockedTarget(matched.x, matched.y, 0)
            correct(matched.x, matched.y, cx, cy, cfg)
        } else {
            val misses = current.missCount + 1
            if (misses >= cfg.releaseAfterMisses) release()
            else target = current.copy(missCount = misses)
        }
    }

    fun stop() {
        release()
    }

    private fun correct(tx: Int, ty: Int, cx: Int, cy: Int, cfg: ScreenCenterConfig) {
        var dx = tx - cx
        var dy = ty - cy
        if (cfg.invertX) dx = -dx
        if (cfg.invertY) dy = -dy

        if (abs(dx) <= cfg.deadzonePx && abs(dy) <= cfg.deadzonePx) return

        val mx = (dx * cfg.sensitivity).toInt().coerceIn(-cfg.maxStepPx, cfg.maxStepPx)
        val my = (dy * cfg.sensitivity).toInt().coerceIn(-cfg.maxStepPx, cfg.maxStepPx)
        if (mx == 0 && my == 0) return

        injector.moveBy(mx, my)
    }

    private fun release() {
        if (target == null) return
        injector.release()
        target = null
    }

    private fun nearestTo(detections: List<DetectResult>, px: Int, py: Int): DetectResult? =
        detections.minByOrNull { d -> distSq(d.x, d.y, px, py) }

    private fun distSq(x1: Int, y1: Int, x2: Int, y2: Int): Long {
        val dx = (x1 - x2).toLong()
        val dy = (y1 - y2).toLong()
        return dx * dx + dy * dy
    }
}
