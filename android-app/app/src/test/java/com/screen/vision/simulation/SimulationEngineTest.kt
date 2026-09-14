package com.screen.vision.simulation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SimulationEngineTest {
    @Test
    fun imuTraceIsDeterministicAndRespectsKinematicLimits() {
        val config = ImuSynthesisConfig(
            sampleRateHz = 100.0,
            maximumAngularSpeedRadPerSec = 2.0,
            maximumAngularAccelerationRadPerSec2 = 10.0,
        )
        val synthesizer = ImuPhysicsSynthesizer(config)

        val first = synthesizer.synthesize(Vector3(0.4, -0.2, 0.1), 300_000_000L, seed = 42L)
        val second = synthesizer.synthesize(Vector3(0.4, -0.2, 0.1), 300_000_000L, seed = 42L)

        assertEquals(first, second)
        assertTrue(first.all { it.angularVelocityRadPerSec.magnitude <= 2.0 + EPSILON })
        first.zipWithNext().forEach { (a, b) ->
            val dt = (b.timeNs - a.timeNs) / NANOS_PER_SECOND
            val acceleration = (b.angularVelocityRadPerSec - a.angularVelocityRadPerSec).magnitude / dt
            assertTrue(acceleration <= 10.0 + EPSILON)
        }
    }

    @Test
    fun imuSingleAxisMotionProducesConfiguredCrossAxisCoupling() {
        val synthesizer = ImuPhysicsSynthesizer(
            ImuSynthesisConfig(
                whiteNoiseStdRadPerSec = 0.0,
                tremorAmplitudeRadPerSec = 0.0,
                couplingRatioMin = 0.1,
                couplingRatioMax = 0.1,
            ),
        )

        val samples = synthesizer.synthesize(Vector3(0.4, 0.0, 0.0), 500_000_000L, seed = 7L)

        assertTrue(samples.any { abs(it.angularVelocityRadPerSec.y) > EPSILON })
        assertTrue(samples.any { abs(it.angularVelocityRadPerSec.z) > EPSILON })
    }

    @Test
    fun touchTraceHasExactEndpointsDynamicAttributesAndStableSeed() {
        val synthesizer = TouchPathSynthesizer()
        val first = synthesizer.synthesize(Point2(10.0, 20.0), Point2(410.0, 180.0), 400_000_000L, seed = 9L)
        val second = synthesizer.synthesize(Point2(10.0, 20.0), Point2(410.0, 180.0), 400_000_000L, seed = 9L)

        assertEquals(first, second)
        assertEquals(SimulatedTouchPhase.DOWN, first.first().phase)
        assertEquals(SimulatedTouchPhase.UP, first.last().phase)
        assertEquals(10f, first.first().xPx, 0f)
        assertEquals(20f, first.first().yPx, 0f)
        assertEquals(410f, first.last().xPx, 0f)
        assertEquals(180f, first.last().yPx, 0f)
        assertTrue(first.all { it.pressure in 0.05f..1f && it.size in 0.01f..1f })
        assertTrue(first.map { it.pressure }.distinct().size > 1)
        assertTrue(first.all { it.touchMajorPx >= it.touchMinorPx && it.touchMinorPx >= 1f })
    }

    @Test
    fun coordinatorPreservesTotalDeltaAndUsesDeadzoneHysteresis() {
        val coordinator = SpatiotemporalConsistencyCoordinator()
        val large = coordinator.plan(Math.toRadians(20.0), Math.toRadians(-5.0))

        assertFalse(large.inDeadzone)
        assertEquals(Math.toRadians(20.0), large.touchAngularDelta.x + large.gyroAngularDelta.x, EPSILON)
        assertEquals(Math.toRadians(-5.0), large.touchAngularDelta.y + large.gyroAngularDelta.y, EPSILON)
        assertTrue(abs(large.touchAngularDelta.x) > abs(large.gyroAngularDelta.x))

        val entered = coordinator.plan(Math.toRadians(0.5), 0.0)
        val retained = coordinator.plan(Math.toRadians(1.0), 0.0)
        val exited = coordinator.plan(Math.toRadians(1.4), 0.0)
        assertTrue(entered.inDeadzone)
        assertTrue(retained.inDeadzone)
        assertFalse(exited.inDeadzone)
    }

    @Test
    fun integratedEngineBuildsTimeAlignedDualModalTrace() {
        val engine = InteractiveSimulationEngine()
        val trace = engine.synthesize(
            SimulationRequest(
                yawDeltaRad = Math.toRadians(12.0),
                pitchDeltaRad = Math.toRadians(3.0),
                touchStartPx = Point2(900.0, 500.0),
                horizontalPixelsPerRad = 800.0,
                verticalPixelsPerRad = 800.0,
                requestedDurationNs = 400_000_000L,
                startTimeNs = 2_000_000_000L,
                seed = 123L,
            ),
        )

        assertFalse(trace.plan.inDeadzone)
        assertTrue(trace.touchSamples.isNotEmpty())
        assertTrue(trace.imuSamples.isNotEmpty())
        assertEquals(2_000_000_000L, trace.touchSamples.first().timeNs)
        assertEquals(2_000_000_000L, trace.imuSamples.first().timeNs)
    }

    companion object {
        private const val EPSILON = 1e-6
        private const val NANOS_PER_SECOND = 1_000_000_000.0
    }
}
