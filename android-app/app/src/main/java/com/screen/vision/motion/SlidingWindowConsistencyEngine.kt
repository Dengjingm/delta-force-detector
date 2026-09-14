package com.screen.vision.motion

import java.util.ArrayDeque

/**
 * Thread-safe bounded buffer around [MultimodalConsistencyAnalyzer]. A report is produced only
 * when an independent view observation has been submitted; touch or gyro never fabricate it.
 */
class SlidingWindowConsistencyEngine(
    calibration: MotionCalibration,
    private val windowLengthNs: Long = 250_000_000L,
    private val maximumSamplesPerChannel: Int = 4096,
    config: ConsistencyConfig = ConsistencyConfig(),
) {
    private val analyzer = MultimodalConsistencyAnalyzer(calibration, config)
    private val touch = ArrayDeque<TouchSample>()
    private val gyro = ArrayDeque<GyroSample>()
    private val view = ArrayDeque<ViewSample>()

    init {
        require(windowLengthNs > 0L)
        require(maximumSamplesPerChannel >= 2)
    }

    @Synchronized
    fun addTouch(sample: TouchSample) {
        touch.addLast(sample)
        trimToCapacity(touch)
        trimByTime(sample.stamp.eventTimeNs)
    }

    @Synchronized
    fun addGyro(sample: GyroSample) {
        gyro.addLast(sample)
        trimToCapacity(gyro)
        trimByTime(sample.stamp.eventTimeNs)
    }

    @Synchronized
    fun addViewAndAnalyze(sample: ViewSample): ConsistencyReport? {
        view.addLast(sample)
        trimToCapacity(view)
        trimByTime(sample.stamp.eventTimeNs)
        if (view.size < 2) return null
        return analyzer.analyze(InteractionWindow(touch.toList(), gyro.toList(), view.toList()))
    }

    @Synchronized
    fun stats(): MotionBufferStats = MotionBufferStats(touch.size, gyro.size, view.size)

    @Synchronized
    fun clear() {
        touch.clear()
        gyro.clear()
        view.clear()
    }

    private fun trimByTime(latestTimeNs: Long) {
        val cutoffNs = latestTimeNs - windowLengthNs
        trimBefore(touch, cutoffNs) { it.stamp.eventTimeNs }
        trimBefore(gyro, cutoffNs) { it.stamp.eventTimeNs }
        trimBefore(view, cutoffNs) { it.stamp.eventTimeNs }
    }

    private fun <T> trimBefore(queue: ArrayDeque<T>, cutoffNs: Long, timeOf: (T) -> Long) {
        while (queue.isNotEmpty() && timeOf(queue.first) < cutoffNs) queue.removeFirst()
    }

    private fun <T> trimToCapacity(queue: ArrayDeque<T>) {
        while (queue.size > maximumSamplesPerChannel) queue.removeFirst()
    }
}

data class MotionBufferStats(
    val touchSamples: Int,
    val gyroSamples: Int,
    val viewSamples: Int,
)
