package com.screen.vision.control

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import java.io.OutputStream

/**
 * App→daemon 反向控制 socket 客户端 (AF_UNIX)。
 *
 * 连接 `/data/local/tmp/screen-vision-ctrl.sock`, 发送编码后的控制命令。
 * 与数据 socket 分离, 不污染像素流 (CONTRACTS C01)。
 */
class ControlClient(private val socketPath: String = CTRL_SOCKET_PATH) {

    private var socket: LocalSocket? = null
    private var outputStream: OutputStream? = null

    val isConnected: Boolean get() = socket != null && outputStream != null

    fun connect(): Boolean {
        if (isConnected) return true
        return try {
            val s = LocalSocket()
            s.connect(LocalSocketAddress(socketPath, LocalSocketAddress.Namespace.FILESYSTEM))
            outputStream = s.outputStream
            socket = s
            true
        } catch (e: Exception) {
            Log.e(TAG, "Control socket connect failed: ${e.message}")
            false
        }
    }

    fun send(bytes: ByteArray): Boolean {
        val os = outputStream ?: return false
        return try {
            os.write(bytes)
            os.flush()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Control send failed: ${e.message}")
            false
        }
    }

    fun close() {
        try { outputStream?.close() } catch (_: Exception) { }
        try { socket?.close() } catch (_: Exception) { }
        outputStream = null
        socket = null
    }

    companion object {
        private const val TAG = "ControlClient"
        const val CTRL_SOCKET_PATH = "/data/local/tmp/screen-vision-ctrl.sock"
    }
}
