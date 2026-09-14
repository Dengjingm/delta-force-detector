package com.screen.vision.center

/**
 * 连续按住式瞄准注入抽象。
 *
 * 一次锁定对应一次按住手势：acquire 按下、moveBy 逐帧相对位移、release 抬起。
 * 当前由 [TouchInjector] 经 shell 实现，后续由 daemon 内 uinput 实现替换。
 */
interface AimInjector {
    fun acquire(anchorX: Int, anchorY: Int)
    fun moveBy(dx: Int, dy: Int)
    fun release()
}
