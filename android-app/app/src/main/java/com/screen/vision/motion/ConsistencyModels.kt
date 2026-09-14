package com.screen.vision.motion

data class ConsistencyConfig(
    val minimumWindowNs: Long = 80_000_000L,
    val maximumWindowNs: Long = 500_000_000L,
    val maximumReceiveDelayNs: Long = 100_000_000L,
    val minimumGyroSamples: Int = 3,
    val minimumViewSamples: Int = 2,
    val meaningfulViewDeltaRad: Float = 0.02f,
    val meaningfulInputDeltaRad: Float = 0.006f,
    val residualWarningRad: Float = 0.05f,
    val minimumDirectionCosine: Float = 0.35f,
    val maximumAngularSpeedRadPerSec: Float = 5.24f,
    val maximumAngularAccelerationRadPerSec2: Float = 80f,
)

enum class ConsistencyStatus { CONSISTENT, REVIEW, ANOMALOUS, INDETERMINATE }

enum class FindingCode {
    INVALID_NUMERIC_VALUE,
    NON_MONOTONIC_TIME,
    MIXED_SESSION,
    MIXED_TRANSFORM,
    STALE_EVENT,
    WINDOW_OUT_OF_RANGE,
    INSUFFICIENT_VIEW_DATA,
    INSUFFICIENT_GYRO_DATA,
    VIEW_MOTION_WITHOUT_INPUT,
    VIEW_INPUT_RESIDUAL,
    OPPOSING_DIRECTION,
    ANGULAR_SPEED_LIMIT,
    ANGULAR_ACCELERATION_LIMIT,
}

data class ConsistencyFinding(
    val code: FindingCode,
    val contribution: Int,
    val detail: String,
)

data class ConsistencyMetrics(
    val windowDurationNs: Long,
    val touchYawDeltaRad: Float,
    val touchPitchDeltaRad: Float,
    val gyroYawDeltaRad: Float,
    val gyroPitchDeltaRad: Float,
    val viewYawDeltaRad: Float,
    val viewPitchDeltaRad: Float,
    val residualMagnitudeRad: Float,
    val directionCosine: Float?,
    val peakAngularSpeedRadPerSec: Float,
    val peakAngularAccelerationRadPerSec2: Float,
)

data class ConsistencyReport(
    val status: ConsistencyStatus,
    val riskScore: Int,
    val metrics: ConsistencyMetrics?,
    val findings: List<ConsistencyFinding>,
)
