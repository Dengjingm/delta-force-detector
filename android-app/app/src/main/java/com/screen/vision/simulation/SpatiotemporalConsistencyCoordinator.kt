package com.screen.vision.simulation

import kotlin.math.hypot

data class CoordinationConfig(
    val deadzoneEnterRad: Double = Math.toRadians(0.8),
    val deadzoneExitRad: Double = Math.toRadians(1.2),
    val coarseBlendStartRad: Double = Math.toRadians(2.0),
    val coarseBlendFullRad: Double = Math.toRadians(18.0),
)

/** Plans an in-memory test scenario without dispatching either channel. */
class SpatiotemporalConsistencyCoordinator(
    private val config: CoordinationConfig = CoordinationConfig(),
) {
    private var holding = false

    init {
        require(config.deadzoneEnterRad >= 0.0)
        require(config.deadzoneExitRad >= config.deadzoneEnterRad)
        require(config.coarseBlendStartRad >= config.deadzoneEnterRad)
        require(config.coarseBlendFullRad > config.coarseBlendStartRad)
    }

    fun plan(targetYawDeltaRad: Double, targetPitchDeltaRad: Double): DualModalPlan {
        val magnitude = hypot(targetYawDeltaRad, targetPitchDeltaRad)
        holding = if (holding) magnitude <= config.deadzoneExitRad else magnitude <= config.deadzoneEnterRad
        if (holding) {
            return DualModalPlan(
                touchAngularDelta = Point2(0.0, 0.0),
                gyroAngularDelta = Point2(0.0, 0.0),
                retainedPhysicalGyroWeight = 1.0,
                inDeadzone = true,
            )
        }

        val normalized = ((magnitude - config.coarseBlendStartRad) /
            (config.coarseBlendFullRad - config.coarseBlendStartRad)).coerceIn(0.0, 1.0)
        val coarseWeight = smoothStep(normalized)
        val fineWeight = 1.0 - coarseWeight
        val convergence = (magnitude / config.deadzoneExitRad).coerceIn(0.0, 1.0)
        return DualModalPlan(
            touchAngularDelta = Point2(targetYawDeltaRad * coarseWeight, targetPitchDeltaRad * coarseWeight),
            gyroAngularDelta = Point2(targetYawDeltaRad * fineWeight, targetPitchDeltaRad * fineWeight),
            retainedPhysicalGyroWeight = 1.0 - smoothStep(convergence),
            inDeadzone = false,
        )
    }

    fun reset() {
        holding = false
    }

    private fun smoothStep(value: Double): Double = value * value * (3.0 - 2.0 * value)
}
