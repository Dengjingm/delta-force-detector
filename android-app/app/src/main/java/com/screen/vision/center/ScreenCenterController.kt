package com.screen.vision.center

import com.screen.vision.model.DetectResult
import kotlin.math.abs
import kotlin.random.Random

/**
 * 屏幕中心移动控制器：粘滞锁定 + 每检测帧一次修正。
 *
 * 无锁定时选离屏幕中心最近的人并按下（acquire）。若该人同时有小框和大框
 * （小框中心落在大框内且面积明显更小），按 [ScreenCenterConfig.preferSmallBoxProbability]
 * 在获取时抽一次：默认 80% 跟小框、20% 跟大框；只有大框则跟大框。
 * 此后每帧用中心距离关联同一目标持续跟踪，不再重抽。
 * 连续 [ScreenCenterConfig.releaseAfterMisses] 帧未匹配或停用时抬起（release）。
 *
 * [config] 为 @Volatile, 每帧顶部原子读取一次供整帧使用, 支持运行中热更新。
 */
class ScreenCenterController(
    private val injector: AimInjector = TouchInjector(),
    config: ScreenCenterConfig = ScreenCenterConfig(),
    private val random: Random = Random.Default,
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
            val acquired = pickAcquireTarget(detections, cx, cy, cfg) ?: return
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

    private fun pickAcquireTarget(
        detections: List<DetectResult>,
        cx: Int,
        cy: Int,
        cfg: ScreenCenterConfig,
    ): DetectResult? {
        val nearest = nearestTo(detections, cx, cy) ?: return null
        val small = containedSmallerBox(nearest, detections)
        val large = containingLargerBox(nearest, detections)
        val pairSmall: DetectResult
        val pairLarge: DetectResult
        when {
            small != null -> {
                pairSmall = small
                pairLarge = nearest
            }
            large != null -> {
                pairSmall = nearest
                pairLarge = large
            }
            else -> return nearest
        }
        val p = cfg.preferSmallBoxProbability.coerceIn(0f, 1f)
        return if (random.nextFloat() < p) pairSmall else pairLarge
    }

    /** 锚点为大框时，取其内部面积明显更小的框（通常是头）。 */
    private fun containedSmallerBox(
        large: DetectResult,
        detections: List<DetectResult>,
    ): DetectResult? {
        val largeArea = area(large)
        if (largeArea <= 0f) return null
        return detections
            .filter { it !== large && area(it) > 0f && area(it) <= largeArea * SMALL_AREA_RATIO }
            .filter { containsCenter(large, it) }
            .minByOrNull { area(it) }
    }

    /** 锚点为小框时，找包含其中心且明显更大的框（通常是身体）。 */
    private fun containingLargerBox(
        small: DetectResult,
        detections: List<DetectResult>,
    ): DetectResult? {
        val smallArea = area(small)
        if (smallArea <= 0f) return null
        return detections
            .filter { it !== small && area(it) > 0f && smallArea <= area(it) * SMALL_AREA_RATIO }
            .filter { containsCenter(it, small) }
            .minByOrNull { area(it) }
    }

    private fun nearestTo(detections: List<DetectResult>, px: Int, py: Int): DetectResult? =
        detections.minByOrNull { d -> distSq(d.x, d.y, px, py) }

    private fun distSq(x1: Int, y1: Int, x2: Int, y2: Int): Long {
        val dx = (x1 - x2).toLong()
        val dy = (y1 - y2).toLong()
        return dx * dx + dy * dy
    }

    private fun area(d: DetectResult): Float {
        val w = d.x2 - d.x1
        val h = d.y2 - d.y1
        return if (w > 0f && h > 0f) w * h else 0f
    }

    private fun containsCenter(box: DetectResult, inner: DetectResult): Boolean {
        val x = inner.x.toFloat()
        val y = inner.y.toFloat()
        return x >= box.x1 && x <= box.x2 && y >= box.y1 && y <= box.y2
    }

    companion object {
        /** 小框面积不超过大框的该比例才视为头/身体配对，避免两个相近人体框互配。 */
        const val SMALL_AREA_RATIO = 0.5f
    }
}
