package com.screen.vision.simulation

import kotlin.math.sqrt

data class Vector3(
    val x: Double,
    val y: Double,
    val z: Double,
) {
    operator fun plus(other: Vector3) = Vector3(x + other.x, y + other.y, z + other.z)
    operator fun minus(other: Vector3) = Vector3(x - other.x, y - other.y, z - other.z)
    operator fun times(scale: Double) = Vector3(x * scale, y * scale, z * scale)
    val magnitude: Double get() = sqrt(x * x + y * y + z * z)

    fun limitedTo(maximumMagnitude: Double): Vector3 {
        val length = magnitude
        return if (length <= maximumMagnitude || length == 0.0) this else this * (maximumMagnitude / length)
    }
}

data class SimulatedImuSample(
    val timeNs: Long,
    val angularVelocityRadPerSec: Vector3,
)

enum class SimulatedTouchPhase { DOWN, MOVE, UP }

data class SimulatedTouchSample(
    val timeNs: Long,
    val xPx: Float,
    val yPx: Float,
    val pressure: Float,
    val size: Float,
    val touchMajorPx: Float,
    val touchMinorPx: Float,
    val phase: SimulatedTouchPhase,
)

data class Point2(val x: Double, val y: Double)

data class DualModalPlan(
    val touchAngularDelta: Point2,
    val gyroAngularDelta: Point2,
    val retainedPhysicalGyroWeight: Double,
    val inDeadzone: Boolean,
)
