package com.screen.vision.control

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class ControlProtocolTest {

    // Golden ACQUIRE(100,200): 53564332 0100 0100 08000000 64000000 c8000000
    private val goldenAcquire = byteArrayOf(
        0x53, 0x56, 0x43, 0x32,
        0x01, 0x00,
        0x01, 0x00,
        0x08, 0x00, 0x00, 0x00,
        0x64, 0x00, 0x00, 0x00,
        0xc8.toByte(), 0x00, 0x00, 0x00,
    )

    @Test
    fun encodeAcquireMatchesGoldenBytes() {
        val encoded = ControlProtocol.encode(ControlCommandType.ACQUIRE, 100, 200)
        assertArrayEquals(goldenAcquire, encoded)
    }

    @Test
    fun decodeRoundtripsNegativeCoordinates() {
        val encoded = ControlProtocol.encode(ControlCommandType.MOVE, -1, -32768)
        val cmd = ControlProtocol.decode(encoded)
        assertEquals(ControlCommandType.MOVE, cmd.type)
        assertEquals(-1, cmd.x)
        assertEquals(-32768, cmd.y)
    }

    @Test
    fun decodeRejectsBadMagic() {
        val bytes = ControlProtocol.encode(ControlCommandType.ACQUIRE, 1, 2)
        bytes[0] = 'X'.code.toByte()
        try {
            ControlProtocol.decode(bytes)
            fail("expected ControlProtocolException")
        } catch (e: ControlProtocolException) {
            // expected
        }
    }

    @Test
    fun decodeRejectsUnknownType() {
        val bytes = ControlProtocol.encode(ControlCommandType.RELEASE, 0, 0)
        bytes[6] = 0x63 // type low byte -> 99, unknown
        try {
            ControlProtocol.decode(bytes)
            fail("expected ControlProtocolException")
        } catch (e: ControlProtocolException) {
            // expected
        }
    }
}
