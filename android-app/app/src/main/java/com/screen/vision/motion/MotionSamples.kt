package com.screen.vision.motion

/** Monotonic-clock metadata shared by every input channel. */
data class EventStamp(
    val eventTimeNs: Long,
    val receiveTimeNs: Long,
    val sessionId: Long,
    val transformGeneration: Long,
)

enum class TouchPhase { DOWN, MOVE, UP, CANCEL }

data class TouchSample(
    val stamp: EventStamp,
    val xPx: Float,
    val yPx: Float,
    val phase: TouchPhase,
)

data class AngularVelocity(
    val xRadPerSec: Float,
    val yRadPerSec: Float,
    val zRadPerSec: Float,
)

data class GyroSample(
    val stamp: EventStamp,
    val velocity: AngularVelocity,
)

/** Absolute camera orientation in a locally unwrapped yaw/pitch coordinate system. */
data class ViewSample(
    val stamp: EventStamp,
    val yawRad: Float,
    val pitchRad: Float,
)

data class InteractionWindow(
    val touch: List<TouchSample>,
    val gyro: List<GyroSample>,
    val view: List<ViewSample>,
)

/**
 * Device/profile-specific mapping measured by calibration. Values deliberately have no defaults:
 * a detector must not treat one phone's sensitivity or axis signs as universal.
 */
data class MotionCalibration(
    val touchYawRadPerPixel: Float,
    val touchPitchRadPerPixel: Float,
    val gyroYawAxis: GyroAxis = GyroAxis.Y,
    val gyroPitchAxis: GyroAxis = GyroAxis.X,
    val gyroYawSign: Float = 1f,
    val gyroPitchSign: Float = 1f,
)

enum class GyroAxis { X, Y, Z }
