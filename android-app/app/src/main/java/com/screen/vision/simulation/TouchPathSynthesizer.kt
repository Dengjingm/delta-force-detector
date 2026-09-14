package com.screen.vision.simulation

import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max

data class TouchSynthesisConfig(
    val sampleRateHz: Double = 120.0,
    val controlPointJitterFraction: Double = 0.08,
    val basePressure: Double = 0.42,
    val pressureSpeedGain: Double = 0.055,
    val baseSize: Double = 0.12,
    val sizeSpeedGain: Double = 0.012,
    val baseTouchMajorPx: Double = 18.0,
    val majorSpeedGain: Double = 1.5,
    val minorToMajorRatio: Double = 0.72,
    val attributeNoiseStd: Double = 0.008,
    val speedReferencePxPerSec: Double = 500.0,
)

/** Generates a cubic-Bezier touch trace as data; it does not inject system input. */
class TouchPathSynthesizer(private val config: TouchSynthesisConfig = TouchSynthesisConfig()) {
    init {
        require(config.sampleRateHz > 0.0)
        require(config.controlPointJitterFraction >= 0.0)
        require(config.basePressure in 0.0..1.0)
        require(config.baseSize in 0.0..1.0)
        require(config.baseTouchMajorPx > 0.0)
        require(config.minorToMajorRatio in 0.0..1.0)
        require(config.attributeNoiseStd >= 0.0)
        require(config.speedReferencePxPerSec > 0.0)
    }

    fun synthesize(
        start: Point2,
        end: Point2,
        durationNs: Long,
        startTimeNs: Long = 0L,
        seed: Long = 1L,
    ): List<SimulatedTouchSample> {
        require(durationNs > 0L)
        val random = DeterministicRandom(seed)
        val durationSeconds = durationNs / NANOS_PER_SECOND
        val sampleCount = max(2, ceil(durationSeconds * config.sampleRateHz).toInt() + 1)
        val distance = hypot(end.x - start.x, end.y - start.y)
        val normal = if (distance == 0.0) Point2(0.0, 0.0) else
            Point2(-(end.y - start.y) / distance, (end.x - start.x) / distance)
        val jitterLimit = distance * config.controlPointJitterFraction
        val control1 = Point2(
            start.x + (end.x - start.x) / 3.0 + normal.x * random.uniform(-jitterLimit, jitterLimit),
            start.y + (end.y - start.y) / 3.0 + normal.y * random.uniform(-jitterLimit, jitterLimit),
        )
        val control2 = Point2(
            start.x + 2.0 * (end.x - start.x) / 3.0 + normal.x * random.uniform(-jitterLimit, jitterLimit),
            start.y + 2.0 * (end.y - start.y) / 3.0 + normal.y * random.uniform(-jitterLimit, jitterLimit),
        )

        val output = ArrayList<SimulatedTouchSample>(sampleCount)
        repeat(sampleCount) { index ->
            val progress = index.toDouble() / (sampleCount - 1)
            val point = cubicBezier(start, control1, control2, end, progress)
            val derivative = cubicBezierDerivative(start, control1, control2, end, progress)
            val speed = hypot(derivative.x, derivative.y) / durationSeconds
            val speedTerm = ln(1.0 + speed / config.speedReferencePxPerSec)
            val pressure = (config.basePressure + config.pressureSpeedGain * speedTerm +
                config.attributeNoiseStd * random.gaussian()).coerceIn(0.05, 1.0)
            val size = (config.baseSize + config.sizeSpeedGain * speedTerm +
                config.attributeNoiseStd * random.gaussian()).coerceIn(0.01, 1.0)
            val major = max(1.0, config.baseTouchMajorPx + config.majorSpeedGain * speedTerm)
            val minor = max(1.0, major * config.minorToMajorRatio)
            output += SimulatedTouchSample(
                timeNs = startTimeNs + (progress * durationNs).toLong(),
                xPx = point.x.toFloat(),
                yPx = point.y.toFloat(),
                pressure = pressure.toFloat(),
                size = size.toFloat(),
                touchMajorPx = major.toFloat(),
                touchMinorPx = minor.toFloat(),
                phase = when (index) {
                    0 -> SimulatedTouchPhase.DOWN
                    sampleCount - 1 -> SimulatedTouchPhase.UP
                    else -> SimulatedTouchPhase.MOVE
                },
            )
        }
        return output
    }

    private fun cubicBezier(p0: Point2, p1: Point2, p2: Point2, p3: Point2, t: Double): Point2 {
        val u = 1.0 - t
        return Point2(
            u * u * u * p0.x + 3.0 * u * u * t * p1.x + 3.0 * u * t * t * p2.x + t * t * t * p3.x,
            u * u * u * p0.y + 3.0 * u * u * t * p1.y + 3.0 * u * t * t * p2.y + t * t * t * p3.y,
        )
    }

    private fun cubicBezierDerivative(p0: Point2, p1: Point2, p2: Point2, p3: Point2, t: Double): Point2 {
        val u = 1.0 - t
        return Point2(
            3.0 * u * u * (p1.x - p0.x) + 6.0 * u * t * (p2.x - p1.x) + 3.0 * t * t * (p3.x - p2.x),
            3.0 * u * u * (p1.y - p0.y) + 6.0 * u * t * (p2.y - p1.y) + 3.0 * t * t * (p3.y - p2.y),
        )
    }

    companion object {
        private const val NANOS_PER_SECOND = 1_000_000_000.0
    }
}
