package com.screen.vision.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MultimodalConsistencyAnalyzerTest {

    private val analyzer = MultimodalConsistencyAnalyzer(
        calibration = MotionCalibration(
            touchYawRadPerPixel = 0.001f,
            touchPitchRadPerPixel = 0.001f,
        ),
    )

    @Test
    fun alignedTouchGyroAndViewIsConsistent() {
        val times = listOf(0L, 50L, 100L, 150L, 200L).map(::ms)
        val touch = listOf(
            touch(times[0], 0f, TouchPhase.DOWN),
            touch(times[1], 10f, TouchPhase.MOVE),
            touch(times[2], 20f, TouchPhase.MOVE),
            touch(times[3], 30f, TouchPhase.UP),
        )
        val gyro = times.map { gyro(it, yawRate = 0.1f) }
        val view = times.mapIndexed { index, time -> view(time, yaw = index * 0.0125f) }

        val report = analyzer.analyze(InteractionWindow(touch, gyro, view))

        assertEquals(ConsistencyStatus.CONSISTENT, report.status)
        assertEquals(0, report.riskScore)
        assertNear(0.03f, report.metrics!!.touchYawDeltaRad)
        assertNear(0.02f, report.metrics!!.gyroYawDeltaRad)
        assertNear(0.05f, report.metrics!!.viewYawDeltaRad)
        assertNear(0f, report.metrics!!.residualMagnitudeRad)
    }

    @Test
    fun cameraMotionWithoutTouchOrGyroIsAnomalous() {
        val times = listOf(0L, 50L, 100L, 150L, 200L).map(::ms)
        val gyro = times.map { gyro(it, yawRate = 0f) }
        val view = times.mapIndexed { index, time -> view(time, yaw = index * 0.025f) }

        val report = analyzer.analyze(InteractionWindow(emptyList(), gyro, view))

        assertEquals(ConsistencyStatus.ANOMALOUS, report.status)
        assertTrue(report.findings.any { it.code == FindingCode.VIEW_MOTION_WITHOUT_INPUT })
        assertTrue(report.findings.any { it.code == FindingCode.VIEW_INPUT_RESIDUAL })
    }

    @Test
    fun excessiveViewKinematicsProducesExplainableFindings() {
        val times = listOf(0L, 50L, 100L, 150L).map(::ms)
        val gyro = times.map { gyro(it, yawRate = 2f) }
        val view = listOf(
            view(times[0], 0f),
            view(times[1], 0.05f),
            view(times[2], 0.75f),
            view(times[3], 1.5f),
        )

        val report = analyzer.analyze(InteractionWindow(emptyList(), gyro, view))

        assertEquals(ConsistencyStatus.ANOMALOUS, report.status)
        assertTrue(report.findings.any { it.code == FindingCode.ANGULAR_SPEED_LIMIT })
        assertTrue(report.findings.any { it.code == FindingCode.ANGULAR_ACCELERATION_LIMIT })
    }

    @Test
    fun unorderedChannelIsIndeterminateInsteadOfSilentlySorted() {
        val gyro = listOf(gyro(ms(0), 0f), gyro(ms(100), 0f), gyro(ms(50), 0f))
        val view = listOf(view(ms(0), 0f), view(ms(100), 0f))

        val report = analyzer.analyze(InteractionWindow(emptyList(), gyro, view))

        assertEquals(ConsistencyStatus.INDETERMINATE, report.status)
        assertEquals(null, report.metrics)
        assertTrue(report.findings.any { it.code == FindingCode.NON_MONOTONIC_TIME })
    }

    @Test
    fun mixedTransformGenerationIsIndeterminate() {
        val gyro = listOf(
            gyro(ms(0), 0f, generation = 1),
            gyro(ms(50), 0f, generation = 1),
            gyro(ms(100), 0f, generation = 1),
        )
        val view = listOf(
            view(ms(0), 0f, generation = 1),
            view(ms(100), 0f, generation = 2),
        )

        val report = analyzer.analyze(InteractionWindow(emptyList(), gyro, view))

        assertEquals(ConsistencyStatus.INDETERMINATE, report.status)
        assertTrue(report.findings.any { it.code == FindingCode.MIXED_TRANSFORM })
    }

    @Test
    fun nonFiniteInputIsIndeterminate() {
        val gyro = listOf(gyro(ms(0), 0f), gyro(ms(50), Float.NaN), gyro(ms(100), 0f))
        val view = listOf(view(ms(0), 0f), view(ms(100), 0f))

        val report = analyzer.analyze(InteractionWindow(emptyList(), gyro, view))

        assertEquals(ConsistencyStatus.INDETERMINATE, report.status)
        assertTrue(report.findings.any { it.code == FindingCode.INVALID_NUMERIC_VALUE })
    }

    @Test
    fun slidingWindowProducesReportOnlyAfterSecondViewObservation() {
        val engine = SlidingWindowConsistencyEngine(
            MotionCalibration(0.001f, 0.001f),
        )
        listOf(0L, 50L, 100L, 150L, 200L).map(::ms).forEach {
            engine.addGyro(gyro(it, yawRate = 0.1f))
        }
        engine.addTouch(touch(ms(0), 0f, TouchPhase.DOWN))
        engine.addTouch(touch(ms(100), 20f, TouchPhase.MOVE))
        engine.addTouch(touch(ms(150), 30f, TouchPhase.UP))

        assertEquals(null, engine.addViewAndAnalyze(view(ms(0), 0f)))
        val report = engine.addViewAndAnalyze(view(ms(200), 0.05f))

        assertEquals(ConsistencyStatus.CONSISTENT, report!!.status)
    }

    @Test
    fun slidingWindowKeepsMemoryBounded() {
        val engine = SlidingWindowConsistencyEngine(
            calibration = MotionCalibration(0.001f, 0.001f),
            windowLengthNs = ms(1_000),
            maximumSamplesPerChannel = 3,
        )
        repeat(10) { index -> engine.addGyro(gyro(ms(index.toLong()), 0f)) }

        assertEquals(3, engine.stats().gyroSamples)
    }

    private fun touch(timeNs: Long, x: Float, phase: TouchPhase) = TouchSample(
        stamp(timeNs),
        xPx = x,
        yPx = 0f,
        phase = phase,
    )

    private fun gyro(timeNs: Long, yawRate: Float, generation: Long = 1L) = GyroSample(
        stamp(timeNs, generation),
        AngularVelocity(xRadPerSec = 0f, yRadPerSec = yawRate, zRadPerSec = 0f),
    )

    private fun view(timeNs: Long, yaw: Float, generation: Long = 1L) = ViewSample(
        stamp(timeNs, generation),
        yawRad = yaw,
        pitchRad = 0f,
    )

    private fun stamp(timeNs: Long, generation: Long = 1L) = EventStamp(
        eventTimeNs = timeNs,
        receiveTimeNs = timeNs + ms(1),
        sessionId = 7L,
        transformGeneration = generation,
    )

    private fun ms(value: Long): Long = value * 1_000_000L

    private fun assertNear(expected: Float, actual: Float) {
        assertEquals(expected.toDouble(), actual.toDouble(), 1e-5)
    }
}
