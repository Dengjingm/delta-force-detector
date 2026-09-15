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
        trimChannel(touch) { it.stamp.eventTimeNs }
    }

    @Synchronized
    fun addGyro(sample: GyroSample) {
        gyro.addLast(sample)
        trimToCapacity(gyro)
        trimChannel(gyro) { it.stamp.eventTimeNs }
    }

    @Synchronized
    fun addViewAndAnalyze(sample: ViewSample): ConsistencyReport? {
        view.addLast(sample)
        trimToCapacity(view)
        trimChannel(view) { it.stamp.eventTimeNs }
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

    /** 各通道按自己的最新时间裁剪，避免触控/陀螺仪时钟不一致时互相清空。 */
    private fun <T> trimChannel(queue: ArrayDeque<T>, timeOf: (T) -> Long) {
        if (queue.isEmpty()) return
        trimBefore(queue, timeOf(queue.last) - windowLengthNs, timeOf)
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
