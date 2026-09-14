package com.screen.vision.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LatestSlotTest {

    @Test
    fun publishReturnsNullWhenEmpty() {
        val slot = LatestSlot<String>()
        assertNull(slot.publish("a"))
    }

    @Test
    fun publishReturnsReplacedValue() {
        val slot = LatestSlot<String>()
        slot.publish("a")
        assertEquals("a", slot.publish("b"))
        assertEquals("b", slot.publish("c"))
    }

    @Test
    fun takeReturnsAndClears() {
        val slot = LatestSlot<String>()
        slot.publish("a")
        assertEquals("a", slot.take())
        assertNull(slot.take())
    }

    @Test
    fun clearReturnsAndClears() {
        val slot = LatestSlot<String>()
        slot.publish("a")
        assertEquals("a", slot.clear())
        assertNull(slot.clear())
    }

    @Test
    fun clearOnEmptyReturnsNull() {
        assertNull(LatestSlot<String>().clear())
    }
}
