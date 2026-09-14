package com.screen.vision.socket

import android.graphics.Bitmap
import java.io.InputStream
import java.nio.ByteBuffer

/**
 * 连接到 Native 守护进程的 Unix Socket 客户端。
 *
 * 通过 LocalSocket (AF_UNIX) 连接守护进程, 从守护进程接收逐帧屏幕数据。
 *
 * 协议 (v2, CONTRACTS C01):
 *   [64-byte header (little-endian)][width*height*4 bytes: RGBA pixels]
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

    fun readFrame(): ReceivedFrame? {
        val stream = inputStream ?: return null
        return try {
            val headerBytes = ByteArray(FrameHeader.HEADER_BYTES)
            readFully(stream, headerBytes)
            val header = FrameHeader.decode(headerBytes)

            val pixels = ByteArray(header.payloadBytes.toInt())
            readFully(stream, pixels)

            val bitmap = Bitmap.createBitmap(header.width, header.height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(pixels))
            ReceivedFrame(bitmap, header)
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
    }
}

/** 一帧完整数据：位图 + v2 帧头元数据。 */
data class ReceivedFrame(
    val bitmap: Bitmap,
    val header: FrameHeader,
)
