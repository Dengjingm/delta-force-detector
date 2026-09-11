package com.screen.vision.socket

import android.graphics.Bitmap
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 连接到 Native 守护进程的 Unix Socket 客户端。
 *
 * 通过 LocalSocket (AF_UNIX) 连接守护进程,
 * 从守护进程接收逐帧屏幕数据。
 *
 * 协议:
 *   [4 bytes: width][4 bytes: height][width*height*4 bytes: RGBA pixels]
 */
class UnixSocketClient(private val socketPath: String = SOCKET_PATH) {

    private var socket: android.net.LocalSocket? = null
    private var inputStream: InputStream? = null

    fun connect(): Boolean {
        return try {
            val localSocket = android.net.LocalSocket()
            localSocket.connect(android.net.LocalSocketAddress(
                socketPath,
                android.net.LocalSocketAddress.Namespace.FILESYSTEM
            ))
            inputStream = localSocket.inputStream
            socket = localSocket
            true
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Socket connect failed: ${e.message}")
            false
        }
    }

    fun readFrame(): Bitmap? {
        val stream = inputStream ?: return null
        return try {
            val header = ByteArray(HEADER_SIZE)
            readFully(stream, header)
            val buffer = ByteBuffer.wrap(header).order(ByteOrder.nativeOrder())
            val width = buffer.int
            val height = buffer.int

            if (width <= 0 || height > MAX_DIMENSION) return null

            val pixelCount = width * height * 4
            val pixels = ByteArray(pixelCount)
            readFully(stream, pixels)

            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(pixels))
            bitmap
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Read frame failed: ${e.message}")
            null
        }
    }

    private fun readFully(stream: InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val read = stream.read(buffer, offset, buffer.size - offset)
            if (read == -1) throw java.io.EOFException("Socket closed")
            offset += read
        }
    }

    fun disconnect() {
        try { inputStream?.close() } catch (_: Exception) { }
        try { socket?.close() } catch (_: Exception) { }
        inputStream = null
        socket = null
    }

    companion object {
        private const val TAG = "UnixSocketClient"
        const val SOCKET_PATH = "/data/local/tmp/screen-vision.sock"
        const val HEADER_SIZE = 8
        const val MAX_DIMENSION = 4096
    }
}