package com.screen.vision.socket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FrameHeaderTest {

    // 与 contracts/fixtures/frame_v2_rg_2x1.bin 完全一致的 72 字节 golden
    // (64 头 + 8 负载)。逐字节对齐 C 端 frame_protocol.c 编码结果。
    private val GOLDEN_HEX =
        "5356463202004000080000000200000001000000080000000100000000000000" +
            "0100000000000000e803000000000000d0070000000000000100000000000000" +
            "ff0000ff00ff00ff"

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.filterNot { it.isWhitespace() }
        require(clean.length % 2 == 0) { "odd hex length" }
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    private fun baseHeader(): ByteArray =
        hexToBytes(GOLDEN_HEX).copyOf(FrameHeader.HEADER_BYTES)

    private fun setU32(b: ByteArray, offset: Int, v: Long) {
        b[offset] = (v and 0xFF).toByte()
        b[offset + 1] = ((v shr 8) and 0xFF).toByte()
        b[offset + 2] = ((v shr 16) and 0xFF).toByte()
        b[offset + 3] = ((v shr 24) and 0xFF).toByte()
    }

    private fun setU64(b: ByteArray, offset: Int, v: Long) {
        setU32(b, offset, v and 0xFFFFFFFFL)
        setU32(b, offset + 4, (v ushr 32) and 0xFFFFFFFFL)
    }

    @Test
    fun decodesGoldenFixtureHeaderByteForByte() {
        val header = FrameHeader.decode(hexToBytes(GOLDEN_HEX))

        assertEquals(8L, header.payloadBytes)
        assertEquals(2, header.width)
        assertEquals(1, header.height)
        assertEquals(8L, header.rowStrideBytes)
        assertEquals(1L, header.pixelFormat)
        assertEquals(0, header.rotationDegrees)
        assertEquals(1L, header.frameId)
        assertEquals(1000L, header.captureStartNs)
        assertEquals(2000L, header.captureEndNs)
        assertEquals(1L, header.streamId)
    }

    @Test
    fun rejectsBadMagic() {
        val b = baseHeader().also { it[0] = 'X'.code.toByte() }
        assertThrows(FrameProtocolException::class.java) { FrameHeader.decode(b) }
    }

    @Test
    fun rejectsUnsupportedVersion() {
        val b = baseHeader().also { it[4] = 0x03 }
        assertThrows(FrameProtocolException::class.java) { FrameHeader.decode(b) }
    }

    @Test
    fun rejectsUnsupportedHeaderSize() {
        val b = baseHeader().also { it[6] = 0x20 }
        assertThrows(FrameProtocolException::class.java) { FrameHeader.decode(b) }
    }

    @Test
    fun rejectsShortHeader() {
        assertThrows(FrameProtocolException::class.java) {
            FrameHeader.decode(ByteArray(FrameHeader.HEADER_BYTES - 1))
        }
    }

    @Test
    fun rejectsWidthOutOfRange() {
        val zero = baseHeader().also { setU32(it, 12, 0) }
        val tooBig = baseHeader().also { setU32(it, 12, 4097) }
        assertThrows(FrameProtocolException::class.java) { FrameHeader.decode(zero) }
        assertThrows(FrameProtocolException::class.java) { FrameHeader.decode(tooBig) }
    }

    @Test
    fun rejectsHeightOutOfRange() {
        val b = baseHeader().also { setU32(it, 16, 0) }
        assertThrows(FrameProtocolException::class.java) { FrameHeader.decode(b) }
    }

    @Test
    fun rejectsStrideMismatch() {
        val b = baseHeader().also { setU32(it, 20, 16) }
        assertThrows(FrameProtocolException::class.java) { FrameHeader.decode(b) }
    }

    @Test
    fun rejectsUnsupportedPixelFormat() {
        val b = baseHeader().also { setU32(it, 24, 2) }
        assertThrows(FrameProtocolException::class.java) { FrameHeader.decode(b) }
    }

    @Test
    fun rejectsInvalidRotation() {
        val b = baseHeader().also { setU32(it, 28, 45) }
        assertThrows(FrameProtocolException::class.java) { FrameHeader.decode(b) }
    }

    @Test
    fun rejectsPayloadMismatch() {
        val b = baseHeader().also { setU32(it, 8, 7) }
        assertThrows(FrameProtocolException::class.java) { FrameHeader.decode(b) }
    }

    @Test
    fun rejectsTimeOrder() {
        val b = baseHeader().also { setU64(it, 48, 999) }
        assertThrows(FrameProtocolException::class.java) { FrameHeader.decode(b) }
    }

    @Test
    fun rejectsZeroFrameId() {
        val b = baseHeader().also { setU64(it, 32, 0) }
        assertThrows(FrameProtocolException::class.java) { FrameHeader.decode(b) }
    }

    @Test
    fun rejectsZeroStreamId() {
        val b = baseHeader().also { setU64(it, 56, 0) }
        assertThrows(FrameProtocolException::class.java) { FrameHeader.decode(b) }
    }
}
