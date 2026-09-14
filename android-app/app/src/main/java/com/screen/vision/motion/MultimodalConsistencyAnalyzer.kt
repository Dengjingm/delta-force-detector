package com.screen.vision.motion

import kotlin.math.hypot

/**
 * Offline/diagnostic consistency analysis for real touch, gyro and camera observations.
 * It reports explainable evidence and never generates or injects input events.
 */
class MultimodalConsistencyAnalyzer(
    private val calibration: MotionCalibration,
    private val config: ConsistencyConfig = ConsistencyConfig(),
) {

    init {
        require(calibration.touchYawRadPerPixel.isFinite())
        require(calibration.touchPitchRadPerPixel.isFinite())
        require(calibration.gyroYawSign == 1f || calibration.gyroYawSign == -1f)
        require(calibration.gyroPitchSign == 1f || calibration.gyroPitchSign == -1f)
        require(config.minimumWindowNs > 0L)
        require(config.maximumWindowNs >= config.minimumWindowNs)
        require(config.minimumGyroSamples >= 2)
        require(config.minimumViewSamples >= 2)
        require(config.maximumReceiveDelayNs >= 0L)
        require(config.meaningfulViewDeltaRad >= 0f)
        require(config.meaningfulInputDeltaRad >= 0f)
        require(config.residualWarningRad >= 0f)
        require(config.minimumDirectionCosine in -1f..1f)
        require(config.maximumAngularSpeedRadPerSec > 0f)
        require(config.maximumAngularAccelerationRadPerSec2 > 0f)
    }

    fun analyze(window: InteractionWindow): ConsistencyReport {
        val findings = mutableListOf<ConsistencyFinding>()
        val allStamps = buildList {
            addAll(window.touch.map { it.stamp })
            addAll(window.gyro.map { it.stamp })
            addAll(window.view.map { it.stamp })
        }

        if (window.view.size < config.minimumViewSamples) {
            findings += finding(FindingCode.INSUFFICIENT_VIEW_DATA, 0, "view samples=${window.view.size}")
        }
        if (window.gyro.size < config.minimumGyroSamples) {
            findings += finding(FindingCode.INSUFFICIENT_GYRO_DATA, 0, "gyro samples=${window.gyro.size}")
        }
        if (!hasFiniteValues(window)) {
            findings += finding(FindingCode.INVALID_NUMERIC_VALUE, 0, "a coordinate, orientation or angular velocity is not finite")
        }
        if (!isStrictlyIncreasing(window.touch.map { it.stamp.eventTimeNs }) ||
            !isStrictlyIncreasing(window.gyro.map { it.stamp.eventTimeNs }) ||
            !isStrictlyIncreasing(window.view.map { it.stamp.eventTimeNs })
        ) {
            findings += finding(FindingCode.NON_MONOTONIC_TIME, 0, "at least one channel is unordered or duplicated")
        }
        if (allStamps.map { it.sessionId }.distinct().size > 1) {
            findings += finding(FindingCode.MIXED_SESSION, 0, "samples span multiple sessions")
        }
        if (allStamps.map { it.transformGeneration }.distinct().size > 1) {
            findings += finding(FindingCode.MIXED_TRANSFORM, 0, "samples span multiple display transforms")
        }
        if (allStamps.any { it.receiveTimeNs < it.eventTimeNs || it.receiveTimeNs - it.eventTimeNs > config.maximumReceiveDelayNs }) {
            findings += finding(FindingCode.STALE_EVENT, 0, "receive delay is negative or exceeds the configured limit")
        }

        val fatal = findings.any { it.code in METADATA_FAILURES }
        if (fatal || window.view.size < config.minimumViewSamples || window.gyro.size < config.minimumGyroSamples) {
            return ConsistencyReport(ConsistencyStatus.INDETERMINATE, 0, null, findings)
        }

        val startNs = window.view.first().stamp.eventTimeNs
        val endNs = window.view.last().stamp.eventTimeNs
        val durationNs = endNs - startNs
        if (durationNs !in config.minimumWindowNs..config.maximumWindowNs) {
            findings += finding(FindingCode.WINDOW_OUT_OF_RANGE, 0, "durationNs=$durationNs")
            return ConsistencyReport(ConsistencyStatus.INDETERMINATE, 0, null, findings)
        }

        val alignedTouch = window.touch.filter { it.stamp.eventTimeNs in startNs..endNs }
        val alignedGyro = window.gyro.filter { it.stamp.eventTimeNs in startNs..endNs }
        if (alignedGyro.size < config.minimumGyroSamples) {
            findings += finding(
                FindingCode.INSUFFICIENT_GYRO_DATA,
                0,
                "gyro samples inside view interval=${alignedGyro.size}",
            )
            return ConsistencyReport(ConsistencyStatus.INDETERMINATE, 0, null, findings)
        }

        val touchDelta = touchDelta(alignedTouch)
        val gyroDelta = gyroDelta(alignedGyro)
        val viewDelta = Pair(
            window.view.last().yawRad - window.view.first().yawRad,
            window.view.last().pitchRad - window.view.first().pitchRad,
        )
        val predictedYaw = touchDelta.first + gyroDelta.first
        val predictedPitch = touchDelta.second + gyroDelta.second
        val residual = hypot(viewDelta.first - predictedYaw, viewDelta.second - predictedPitch)
        val viewMagnitude = hypot(viewDelta.first, viewDelta.second)
        val inputMagnitude = hypot(predictedYaw, predictedPitch)
        val directionCosine = cosine(viewDelta.first, viewDelta.second, predictedYaw, predictedPitch)
        val kinematics = viewKinematics(window.view)

        if (viewMagnitude >= config.meaningfulViewDeltaRad && inputMagnitude < config.meaningfulInputDeltaRad) {
            findings += finding(
                FindingCode.VIEW_MOTION_WITHOUT_INPUT,
                55,
                "viewDeltaRad=$viewMagnitude, mappedInputDeltaRad=$inputMagnitude",
            )
        }
        if (viewMagnitude >= config.meaningfulViewDeltaRad && residual > config.residualWarningRad) {
            findings += finding(
                FindingCode.VIEW_INPUT_RESIDUAL,
                25,
                "residualRad=$residual exceeds ${config.residualWarningRad}",
            )
        }
        if (directionCosine != null && viewMagnitude >= config.meaningfulViewDeltaRad &&
            inputMagnitude >= config.meaningfulInputDeltaRad && directionCosine < config.minimumDirectionCosine
        ) {
            findings += finding(FindingCode.OPPOSING_DIRECTION, 20, "directionCosine=$directionCosine")
        }
        if (kinematics.first > config.maximumAngularSpeedRadPerSec) {
            findings += finding(FindingCode.ANGULAR_SPEED_LIMIT, 25, "peakRadPerSec=${kinematics.first}")
        }
        if (kinematics.second > config.maximumAngularAccelerationRadPerSec2) {
            findings += finding(
                FindingCode.ANGULAR_ACCELERATION_LIMIT,
                25,
                "peakRadPerSec2=${kinematics.second}",
            )
        }

        val score = findings.sumOf { it.contribution }.coerceIn(0, 100)
        val status = when {
            score >= 50 -> ConsistencyStatus.ANOMALOUS
            score >= 20 -> ConsistencyStatus.REVIEW
            else -> ConsistencyStatus.CONSISTENT
        }
        val metrics = ConsistencyMetrics(
            windowDurationNs = durationNs,
            touchYawDeltaRad = touchDelta.first,
            touchPitchDeltaRad = touchDelta.second,
            gyroYawDeltaRad = gyroDelta.first,
            gyroPitchDeltaRad = gyroDelta.second,
            viewYawDeltaRad = viewDelta.first,
            viewPitchDeltaRad = viewDelta.second,
            residualMagnitudeRad = residual,
            directionCosine = directionCosine,
            peakAngularSpeedRadPerSec = kinematics.first,
            peakAngularAccelerationRadPerSec2 = kinematics.second,
        )
        return ConsistencyReport(status, score, metrics, findings)
    }

    private fun touchDelta(samples: List<TouchSample>): Pair<Float, Float> {
        var dx = 0f
        var dy = 0f
        var previous: TouchSample? = null
        for (current in samples) {
            when (current.phase) {
                TouchPhase.DOWN -> previous = current
                TouchPhase.MOVE, TouchPhase.UP -> {
                    previous?.let {
                        dx += current.xPx - it.xPx
                        dy += current.yPx - it.yPx
                    }
                    previous = if (current.phase == TouchPhase.UP) null else current
                }
                TouchPhase.CANCEL -> previous = null
            }
        }
        return Pair(dx * calibration.touchYawRadPerPixel, dy * calibration.touchPitchRadPerPixel)
    }

    private fun gyroDelta(samples: List<GyroSample>): Pair<Float, Float> {
        var yaw = 0f
        var pitch = 0f
        for (index in 1 until samples.size) {
            val previous = samples[index - 1]
            val current = samples[index]
            val dtSeconds = (current.stamp.eventTimeNs - previous.stamp.eventTimeNs) / NANOS_PER_SECOND
            val yawRate = (axis(previous.velocity, calibration.gyroYawAxis) +
                axis(current.velocity, calibration.gyroYawAxis)) * 0.5f * calibration.gyroYawSign
            val pitchRate = (axis(previous.velocity, calibration.gyroPitchAxis) +
                axis(current.velocity, calibration.gyroPitchAxis)) * 0.5f * calibration.gyroPitchSign
            yaw += yawRate * dtSeconds
            pitch += pitchRate * dtSeconds
        }
        return Pair(yaw, pitch)
    }

    private fun viewKinematics(samples: List<ViewSample>): Pair<Float, Float> {
        val speeds = ArrayList<Pair<Long, Float>>(samples.size - 1)
        for (index in 1 until samples.size) {
            val previous = samples[index - 1]
            val current = samples[index]
            val dt = (current.stamp.eventTimeNs - previous.stamp.eventTimeNs) / NANOS_PER_SECOND
            val speed = hypot(current.yawRad - previous.yawRad, current.pitchRad - previous.pitchRad) / dt
            speeds += Pair(current.stamp.eventTimeNs, speed)
        }
        var peakAcceleration = 0f
        for (index in 1 until speeds.size) {
            val dt = (speeds[index].first - speeds[index - 1].first) / NANOS_PER_SECOND
            peakAcceleration = maxOf(peakAcceleration, kotlin.math.abs(speeds[index].second - speeds[index - 1].second) / dt)
        }
        return Pair(speeds.maxOfOrNull { it.second } ?: 0f, peakAcceleration)
    }

    private fun axis(value: AngularVelocity, axis: GyroAxis): Float = when (axis) {
        GyroAxis.X -> value.xRadPerSec
        GyroAxis.Y -> value.yRadPerSec
        GyroAxis.Z -> value.zRadPerSec
    }

    private fun cosine(ax: Float, ay: Float, bx: Float, by: Float): Float? {
        val denominator = hypot(ax, ay) * hypot(bx, by)
        if (denominator <= FLOAT_EPSILON) return null
        return ((ax * bx + ay * by) / denominator).coerceIn(-1f, 1f)
    }

    private fun isStrictlyIncreasing(values: List<Long>): Boolean =
        values.size < 2 || values.zipWithNext().all { (a, b) -> b > a }

    private fun hasFiniteValues(window: InteractionWindow): Boolean =
        window.touch.all { it.xPx.isFinite() && it.yPx.isFinite() } &&
            window.gyro.all {
                it.velocity.xRadPerSec.isFinite() &&
                    it.velocity.yRadPerSec.isFinite() &&
                    it.velocity.zRadPerSec.isFinite()
            } &&
            window.view.all { it.yawRad.isFinite() && it.pitchRad.isFinite() }

    private fun finding(code: FindingCode, contribution: Int, detail: String) =
        ConsistencyFinding(code, contribution, detail)

    companion object {
        private const val NANOS_PER_SECOND = 1_000_000_000f
        private const val FLOAT_EPSILON = 1e-9f
        private val METADATA_FAILURES = setOf(
            FindingCode.INVALID_NUMERIC_VALUE,
            FindingCode.NON_MONOTONIC_TIME,
            FindingCode.MIXED_SESSION,
            FindingCode.MIXED_TRANSFORM,
            FindingCode.STALE_EVENT,
        )
    }
}
