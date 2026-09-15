package com.screen.vision.center

/**
 * 把屏幕中心移动画在本页准星上，不注入系统触控。
 * acquire 把准星放到画面中心，moveBy 按控制器给出的步长走向锁定目标。
 */
class VirtualReticleInjector(
    startX: Int = 0,
    startY: Int = 0,
) : AimInjector {

    var x: Int = startX
        private set
    var y: Int = startY
        private set
    var holding: Boolean = false
        private set

    fun reset(startX: Int, startY: Int) {
        x = startX
        y = startY
        holding = false
    }

    override fun acquire(anchorX: Int, anchorY: Int) {
        x = anchorX
        y = anchorY
        holding = true
    }

    override fun moveBy(dx: Int, dy: Int) {
        if (!holding || (dx == 0 && dy == 0)) return
        x += dx
        y += dy
    }

    override fun release() {
        holding = false
    }
}
