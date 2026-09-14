package com.screen.vision.socket

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * v2 帧头解码异常。magic/version/字段语义不合法时抛出。
 */
class FrameProtocolException(message: String) : Exception(message)

/**
 * v2 帧数据协议头（CONTRACTS C01）。64 字节 little-endian。
 *
 * 纯 Kotlin，无 Android 依赖，可 JVM 单测；解码须与
 * contracts/fixtures/frame_v2_rg_2x1.bin 逐字节一致。
 */
data class FrameHeader(
    val payloadBytes: Long,
    val width: Int,
    val height: Int,
    val rowStrideBytes: Long,
    val pixelFormat: Long,
    val rotationDegrees: Int,
    val frameId: Long,
    val captureStartNs: Long,
    val captureEndNs: Long,
    val streamId: Long,
) {
    companion object {
        const val HEADER_BYTES = 64
        const val VERSION = 2
        const val PIXEL_FORMAT_RGBA8888 = 1L
        const val MAX_DIMENSION = 4096
        const val MAX_PAYLOAD_BYTES = 64L * 1024 * 1024

        private val MAGIC = byteArrayOf(
            'S'.code.toByte(), 'V'.code.toByte(), 'F'.code.toByte(), '2'.code.toByte(),
        )

        fun decode(bytes: ByteArray): FrameHeader {
            if (bytes.size < HEADER_BYTES) {
                throw FrameProtocolException("header too short: ${bytes.size} < $HEADER_BYTES")
            }
            val buf = ByteBuffer.wrap(bytes, 0, HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)

            val magic = ByteArray(4)
            buf.get(magic)
            if (!magic.contentEquals(MAGIC)) throw FrameProtocolException("bad magic")

            val version = buf.short.toInt() and 0xFFFF
            if (version != VERSION) throw FrameProtocolException("unsupported version $version")

            val headerBytes = buf.short.toInt() and 0xFFFF
            if (headerBytes != HEADER_BYTES) {
                throw FrameProtocolException("unsupported header size $headerBytes")
            }

            val payloadBytes = buf.int.toLong() and 0xFFFFFFFFL
            val width = buf.int.toLong() and 0xFFFFFFFFL
            val height = buf.int.toLong() and 0xFFFFFFFFL
            val rowStrideBytes = buf.int.toLong() and 0xFFFFFFFFL
            val pixelFormat = buf.int.toLong() and 0xFFFFFFFFL
            val rotationDegrees = buf.int
            val frameId = buf.long
            val captureStartNs = buf.long
            val captureEndNs = buf.long
            val streamId = buf.long

            // 语义校验，镜像 C 端 frame_header_validate
            if (width < 1 || width > MAX_DIMENSION) throw FrameProtocolException("width out of range")
            if (height < 1 || height > MAX_DIMENSION) throw FrameProtocolException("height out of range")
            if (rowStrideBytes != width * 4) throw FrameProtocolException("row stride != width*4")
            if (pixelFormat != PIXEL_FORMAT_RGBA8888) throw FrameProtocolException("unsupported pixel format")
            if (rotationDegrees != 0 && rotationDegrees != 90 && rotationDegrees != 180 && rotationDegrees != 270) {
                throw FrameProtocolException("invalid rotation")
            }
            val expectedPayload = width * height * 4
            if (payloadBytes != expectedPayload) throw FrameProtocolException("payload size mismatch")
            if (payloadBytes > MAX_PAYLOAD_BYTES) throw FrameProtocolException("payload too large")
            if (captureEndNs < captureStartNs) throw FrameProtocolException("capture_end < capture_start")
            if (frameId == 0L) throw FrameProtocolException("zero frame id")
            if (streamId == 0L) throw FrameProtocolException("zero stream id")

            return FrameHeader(
                payloadBytes = payloadBytes,
                width = width.toInt(),
                height = height.toInt(),
                rowStrideBytes = rowStrideBytes,
                pixelFormat = pixelFormat,
                rotationDegrees = rotationDegrees,
                frameId = frameId,
                captureStartNs = captureStartNs,
                captureEndNs = captureEndNs,
                streamId = streamId,
            )
        }
    }
}
