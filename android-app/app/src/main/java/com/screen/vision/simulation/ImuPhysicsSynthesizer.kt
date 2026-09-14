package com.screen.vision.simulation

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

data class ImuSynthesisConfig(
    val sampleRateHz: Double = 100.0,
    val whiteNoiseStdRadPerSec: Double = Math.toRadians(0.12),
    val tremorAmplitudeRadPerSec: Double = Math.toRadians(0.35),
    val tremorFrequencyMinHz: Double = 8.0,
    val tremorFrequencyMaxHz: Double = 12.0,
    val couplingRatioMin: Double = 0.05,
    val couplingRatioMax: Double = 0.15,
    val maximumAngularSpeedRadPerSec: Double = Math.toRadians(300.0),
    val maximumAngularAccelerationRadPerSec2: Double = Math.toRadians(1_200.0),
)

/** Generates deterministic in-memory IMU traces for an application-owned simulation. */
class ImuPhysicsSynthesizer(private val config: ImuSynthesisConfig = ImuSynthesisConfig()) {
    init {
        require(config.sampleRateHz > 0.0)
        require(config.whiteNoiseStdRadPerSec >= 0.0)
        require(config.tremorAmplitudeRadPerSec >= 0.0)
        require(config.tremorFrequencyMinHz in 0.0..config.tremorFrequencyMaxHz)
        require(config.couplingRatioMin in 0.0..config.couplingRatioMax)
        require(config.couplingRatioMax < 1.0)
        require(config.maximumAngularSpeedRadPerSec > 0.0)
        require(config.maximumAngularAccelerationRadPerSec2 > 0.0)
    }

    fun synthesize(
        angularDisplacementRad: Vector3,
        requestedDurationNs: Long,
        startTimeNs: Long = 0L,
        seed: Long = 1L,
    ): List<SimulatedImuSample> {
        require(requestedDurationNs > 0L)
        val durationSeconds = constrainedDurationSeconds(
            angularDisplacementRad.magnitude,
            requestedDurationNs / NANOS_PER_SECOND,
        )
        val intervalSeconds = 1.0 / config.sampleRateHz
        val sampleCount = max(2, ceil(durationSeconds * config.sampleRateHz).toInt() + 1)
        val actualDurationSeconds = (sampleCount - 1) * intervalSeconds
        val random = DeterministicRandom(seed)
        val coupling = couplingMatrix(random)
        val tremorFrequency = Vector3(
            random.uniform(config.tremorFrequencyMinHz, config.tremorFrequencyMaxHz),
            random.uniform(config.tremorFrequencyMinHz, config.tremorFrequencyMaxHz),
            random.uniform(config.tremorFrequencyMinHz, config.tremorFrequencyMaxHz),
        )
        val tremorPhase = Vector3(
            random.uniform(0.0, 2.0 * PI),
            random.uniform(0.0, 2.0 * PI),
            random.uniform(0.0, 2.0 * PI),
        )

        val output = ArrayList<SimulatedImuSample>(sampleCount)
        var previous = Vector3(0.0, 0.0, 0.0)
        repeat(sampleCount) { index ->
            val timeSeconds = index * intervalSeconds
            val progress = (timeSeconds / actualDurationSeconds).coerceIn(0.0, 1.0)
            val base = angularDisplacementRad * (minimumJerkVelocity(progress) / actualDurationSeconds)
            val coupled = applyCoupling(base, coupling)
            val tremor = Vector3(
                sin(2.0 * PI * tremorFrequency.x * timeSeconds + tremorPhase.x),
                sin(2.0 * PI * tremorFrequency.y * timeSeconds + tremorPhase.y),
                sin(2.0 * PI * tremorFrequency.z * timeSeconds + tremorPhase.z),
            ) * config.tremorAmplitudeRadPerSec
            val noise = Vector3(random.gaussian(), random.gaussian(), random.gaussian()) *
                config.whiteNoiseStdRadPerSec
            val requested = (coupled + tremor + noise).limitedTo(config.maximumAngularSpeedRadPerSec)
            val maxStep = config.maximumAngularAccelerationRadPerSec2 * intervalSeconds
            val limited = previous + (requested - previous).limitedTo(maxStep)
            val timeNs = startTimeNs + (timeSeconds * NANOS_PER_SECOND).toLong()
            output += SimulatedImuSample(timeNs, limited)
            previous = limited
        }
        return output
    }

    private fun constrainedDurationSeconds(displacementMagnitude: Double, requested: Double): Double {
        if (displacementMagnitude == 0.0) return requested
        val velocityDuration = MINIMUM_JERK_MAX_VELOCITY * displacementMagnitude /
            config.maximumAngularSpeedRadPerSec
        val accelerationDuration = sqrt(
            MINIMUM_JERK_MAX_ACCELERATION * displacementMagnitude /
                config.maximumAngularAccelerationRadPerSec2,
        )
        return max(requested, max(velocityDuration, accelerationDuration))
    }

    private fun couplingMatrix(random: DeterministicRandom): Array<DoubleArray> = Array(3) { row ->
        DoubleArray(3) { column ->
            if (row == column) 1.0 else random.uniform(config.couplingRatioMin, config.couplingRatioMax) * random.sign()
        }
    }

    private fun applyCoupling(value: Vector3, matrix: Array<DoubleArray>) = Vector3(
        matrix[0][0] * value.x + matrix[0][1] * value.y + matrix[0][2] * value.z,
        matrix[1][0] * value.x + matrix[1][1] * value.y + matrix[1][2] * value.z,
        matrix[2][0] * value.x + matrix[2][1] * value.y + matrix[2][2] * value.z,
    )

    private fun minimumJerkVelocity(progress: Double): Double =
        30.0 * progress * progress * (1.0 - progress) * (1.0 - progress)

    companion object {
        private const val NANOS_PER_SECOND = 1_000_000_000.0
        private const val MINIMUM_JERK_MAX_VELOCITY = 1.875
        private const val MINIMUM_JERK_MAX_ACCELERATION = 5.773502691896258
    }
}
