package com.screen.vision.center

import android.util.Log
import com.screen.vision.control.ControlClient
import com.screen.vision.control.ControlCommandType
import com.screen.vision.control.ControlProtocol

/**
 * daemon 内 input 注入的 [AimInjector] 实现。
 *
 * 经反向控制 socket 发送绝对坐标命令, daemon 顺序同步 exec `input motionevent`。
 * 与 [TouchInjector] 的逐事件 su 进程相比, 消除了多层进程 spawn。
 *
 * 连接/发送失败时置 [isFailed] 并记日志; 上层据此回退 [TouchInjector]。
 */
class DaemonAimInjector(
    private val controlClient: ControlClient = ControlClient(),
) : AimInjector {

    private var anchorX = 0
    private var anchorY = 0
    private var holding = false
    private var failed = false

    val isFailed: Boolean get() = failed

    override fun acquire(anchorX: Int, anchorY: Int) {
        if (failed || holding) return
        if (!controlClient.connect()) {
            failed = true
            Log.e(TAG, "Daemon control connect failed; aiming disabled")
            return
        }
        this.anchorX = anchorX
        this.anchorY = anchorY
        if (!send(ControlCommandType.ACQUIRE, anchorX, anchorY)) {
            failed = true
            controlClient.close()
            return
        }
        holding = true
    }

    override fun moveBy(dx: Int, dy: Int) {
        if (!holding || (dx == 0 && dy == 0)) return
        anchorX += dx
        anchorY += dy
        if (!send(ControlCommandType.MOVE, anchorX, anchorY)) {
            failed = true
            holding = false
            controlClient.close()
        }
    }

    override fun release() {
        if (!holding) return
        holding = false
        if (!send(ControlCommandType.RELEASE, anchorX, anchorY)) failed = true
        controlClient.close()
    }

    private fun send(type: ControlCommandType, x: Int, y: Int): Boolean =
        controlClient.send(ControlProtocol.encode(type, x, y))

    companion object {
        private const val TAG = "DaemonAimInjector"
    }
}
