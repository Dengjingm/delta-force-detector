package com.screen.vision.center

import com.screen.vision.model.DetectResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenCenterControllerTest {

    private class RecordingInjector : AimInjector {
        val events = mutableListOf<String>()
        var acquireCount = 0
        var releaseCount = 0

        override fun acquire(anchorX: Int, anchorY: Int) {
            acquireCount++
            events += "acquire($anchorX,$anchorY)"
        }

        override fun moveBy(dx: Int, dy: Int) {
            events += "move($dx,$dy)"
        }

        override fun release() {
            releaseCount++
            events += "release"
        }
    }

    private fun det(x: Int, y: Int, confidence: Float = 0.9f) = DetectResult(
        elementId = "enemy",
        x = x,
        y = y,
        confidence = confidence,
        x1 = x - 10f,
        y1 = y - 10f,
        x2 = x + 10f,
        y2 = y + 10f,
    )

    @Test
    fun acquirePicksNearestToCenterAndMovesTowardIt() {
        val injector = RecordingInjector()
        val controller = ScreenCenterController(injector)

        controller.onFrame(listOf(det(520, 500), det(900, 900)), 1000, 1000)

        assertEquals(1, injector.acquireCount)
        assertEquals("acquire(500,500)", injector.events.first())
        assertTrue(injector.events.contains("move(20,0)"))
    }

    @Test
    fun stickyLockKeepsTrackingSameTargetInsteadOfReacquiringCenterDecoy() {
        val injector = RecordingInjector()
        val controller = ScreenCenterController(injector)

        controller.onFrame(listOf(det(600, 500)), 1000, 1000)
        // Decoy sits exactly on center; sticky lock must keep tracking the original target.
        controller.onFrame(listOf(det(620, 500), det(500, 500)), 1000, 1000)

        assertEquals(1, injector.acquireCount)
        assertTrue(injector.events.contains("move(60,0)"))
    }

    @Test
    fun releasesAfterConfiguredConsecutiveMisses() {
        val injector = RecordingInjector()
        val controller = ScreenCenterController(
            injector,
            ScreenCenterConfig(releaseAfterMisses = 3),
        )

        controller.onFrame(listOf(det(600, 500)), 1000, 1000)
        controller.onFrame(emptyList(), 1000, 1000)
        controller.onFrame(emptyList(), 1000, 1000)
        assertEquals(0, injector.releaseCount)

        controller.onFrame(emptyList(), 1000, 1000)
        assertEquals(1, injector.releaseCount)
    }

    @Test
    fun deadzoneSuppressesMovementButKeepsHold() {
        val injector = RecordingInjector()
        val controller = ScreenCenterController(
            injector,
            ScreenCenterConfig(deadzonePx = 5),
        )

        controller.onFrame(listOf(det(503, 502)), 1000, 1000)

        assertEquals(1, injector.acquireCount)
        assertTrue(injector.events.none { it.startsWith("move(") })
    }

    @Test
    fun clampsPerFrameStepToMaxStep() {
        val injector = RecordingInjector()
        val controller = ScreenCenterController(
            injector,
            ScreenCenterConfig(sensitivity = 2.0f, maxStepPx = 60),
        )

        controller.onFrame(listOf(det(800, 500)), 1000, 1000)

        assertTrue(injector.events.contains("move(60,0)"))
    }

    @Test
    fun invertXReversesHorizontalCorrection() {
        val injector = RecordingInjector()
        val controller = ScreenCenterController(
            injector,
            ScreenCenterConfig(invertX = true),
        )

        controller.onFrame(listOf(det(600, 500)), 1000, 1000)

        assertTrue(injector.events.contains("move(-60,0)"))
    }

    @Test
    fun stopReleasesHeldTarget() {
        val injector = RecordingInjector()
        val controller = ScreenCenterController(injector)

        controller.onFrame(listOf(det(600, 500)), 1000, 1000)
        controller.stop()

        assertEquals(1, injector.releaseCount)
    }

    @Test
    fun disabledControllerNeverAcquires() {
        val injector = RecordingInjector()
        val controller = ScreenCenterController(
            injector,
            ScreenCenterConfig(enabled = false),
        )

        controller.onFrame(listOf(det(600, 500)), 1000, 1000)

        assertEquals(0, injector.acquireCount)
        assertTrue(injector.events.isEmpty())
    }

    @Test
    fun emptyDetectionsOnFirstFrameDoesNothing() {
        val injector = RecordingInjector()
        val controller = ScreenCenterController(injector)

        controller.onFrame(emptyList(), 1000, 1000)

        assertEquals(0, injector.acquireCount)
        assertTrue(injector.events.isEmpty())
    }
}
