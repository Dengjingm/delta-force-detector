package com.screen.vision.control

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 反向控制协议 codec (App→daemon)。纯 Kotlin, 无 Android 依赖, 可 JVM 单测。
 *
 * 每条命令: [magic "SVC2" 4B][version u16=1][type u16][payloadLen u32][payload i32 x, i32 y]。
 * 全部 little-endian, 与 native-daemon/control_protocol.c 逐字节一致。
 */
class ControlProtocolException(message: String) : Exception(message)

enum class ControlCommandType(val code: Int) {
    ACQUIRE(1),
    MOVE(2),
    RELEASE(3),
}

data class ControlCommand(
    val type: ControlCommandType,
    val x: Int,
    val y: Int,
)

object ControlProtocol {

    const val HEADER_BYTES = 12
    const val VERSION = 1
    const val PAYLOAD_BYTES = 8
    const val MESSAGE_BYTES = HEADER_BYTES + PAYLOAD_BYTES

    private val MAGIC = byteArrayOf(
        'S'.code.toByte(), 'V'.code.toByte(), 'C'.code.toByte(), '2'.code.toByte(),
    )

    fun encode(type: ControlCommandType, x: Int, y: Int): ByteArray {
        val buf = ByteBuffer.allocate(MESSAGE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(MAGIC)
        buf.putShort(VERSION.toShort())
        buf.putShort(type.code.toShort())
        buf.putInt(PAYLOAD_BYTES)
        buf.putInt(x)
        buf.putInt(y)
        return buf.array()
    }

    fun decode(bytes: ByteArray): ControlCommand {
        if (bytes.size < MESSAGE_BYTES) {
            throw ControlProtocolException("message too short: ${bytes.size} < $MESSAGE_BYTES")
        }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        val magic = ByteArray(4)
        buf.get(magic)
        if (!magic.contentEquals(MAGIC)) throw ControlProtocolException("bad magic")

        val version = buf.short.toInt() and 0xFFFF
        if (version != VERSION) throw ControlProtocolException("unsupported version $version")

        val typeCode = buf.short.toInt() and 0xFFFF
        val type = ControlCommandType.values().firstOrNull { it.code == typeCode }
            ?: throw ControlProtocolException("unsupported type $typeCode")

        val payloadLen = buf.int
        if (payloadLen != PAYLOAD_BYTES) {
            throw ControlProtocolException("payload len $payloadLen != $PAYLOAD_BYTES")
        }

        val x = buf.int
        val y = buf.int
        return ControlCommand(type, x, y)
    }
}
