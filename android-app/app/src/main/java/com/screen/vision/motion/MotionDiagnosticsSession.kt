package com.screen.vision.motion

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent

/**
 * Collects real gyroscope events and touch events delivered to the owning Activity. Camera/view
 * orientation must come from an independent, authorized source through [submitViewOrientation].
 */
class MotionDiagnosticsSession(
    context: Context,
    calibration: MotionCalibration,
    private val stateListener: (MotionDiagnosticsState) -> Unit,
) : SensorEventListener {
    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val engine = SlidingWindowConsistencyEngine(calibration)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val sessionId = SystemClock.elapsedRealtimeNanos().coerceAtLeast(1L)
    private val touchClockOffsetNs = SystemClock.elapsedRealtimeNanos() -
        SystemClock.uptimeMillis() * NANOS_PER_MILLISECOND

    @Volatile private var generation = 1L
    @Volatile private var running = false
    @Volatile private var lastReport: ConsistencyReport? = null
    private var lastUiPostNs = 0L
    private var activePointerId = MotionEvent.INVALID_POINTER_ID

    fun start(transformGeneration: Long = 1L): Boolean {
        require(transformGeneration > 0L)
        if (running) return true
        generation = transformGeneration
        engine.clear()
        running = gyroscope != null && sensorManager.registerListener(
            this,
            gyroscope,
            SensorManager.SENSOR_DELAY_GAME,
            mainHandler,
        )
        publishState(force = true)
        return running
    }

    fun stop() {
        if (running) sensorManager.unregisterListener(this)
        running = false
        activePointerId = MotionEvent.INVALID_POINTER_ID
        engine.clear()
        lastReport = null
        publishState(force = true)
    }

    fun updateTransformGeneration(transformGeneration: Long) {
        require(transformGeneration > 0L)
        if (generation == transformGeneration) return
        generation = transformGeneration
        engine.clear()
        lastReport = null
        publishState(force = true)
    }

    /** Records only events already delivered to this app; it does not intercept other apps. */
    fun onTouchEvent(event: MotionEvent) {
        if (!running) return
        val receiveTimeNs = SystemClock.elapsedRealtimeNanos()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointerId = event.getPointerId(event.actionIndex)
                recordTouch(event, event.actionIndex, TouchPhase.DOWN, receiveTimeNs)
            }
            MotionEvent.ACTION_MOVE -> {
                val pointerIndex = event.findPointerIndex(activePointerId)
                if (pointerIndex < 0) return
                for (historyIndex in 0 until event.historySize) {
                    engine.addTouch(
                        TouchSample(
                            stamp = touchStamp(event.getHistoricalEventTime(historyIndex), receiveTimeNs),
                            xPx = event.getHistoricalX(pointerIndex, historyIndex),
                            yPx = event.getHistoricalY(pointerIndex, historyIndex),
                            phase = TouchPhase.MOVE,
                        ),
                    )
                }
                recordTouch(event, pointerIndex, TouchPhase.MOVE, receiveTimeNs)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val pointerIndex = event.actionIndex
                if (event.getPointerId(pointerIndex) != activePointerId) return
                recordTouch(event, pointerIndex, TouchPhase.UP, receiveTimeNs)
                activePointerId = MotionEvent.INVALID_POINTER_ID
            }
            MotionEvent.ACTION_CANCEL -> {
                val pointerIndex = event.findPointerIndex(activePointerId)
                if (pointerIndex >= 0) recordTouch(event, pointerIndex, TouchPhase.CANCEL, receiveTimeNs)
                activePointerId = MotionEvent.INVALID_POINTER_ID
            }
            MotionEvent.ACTION_POINTER_DOWN -> return
            else -> return
        }
        publishState()
    }

    private fun recordTouch(
        event: MotionEvent,
        pointerIndex: Int,
        phase: TouchPhase,
        receiveTimeNs: Long,
    ) {
        engine.addTouch(
            TouchSample(
                stamp = touchStamp(event.eventTime, receiveTimeNs),
                xPx = event.getX(pointerIndex),
                yPx = event.getY(pointerIndex),
                phase = phase,
            ),
        )
    }

    /** Supplies actual view orientation from the application's own renderer or authorized telemetry. */
    fun submitViewOrientation(
        yawRad: Float,
        pitchRad: Float,
        eventTimeNs: Long = SystemClock.elapsedRealtimeNanos(),
    ) {
        if (!running) return
        val receiveTimeNs = SystemClock.elapsedRealtimeNanos()
        lastReport = engine.addViewAndAnalyze(
            ViewSample(monotonicStamp(eventTimeNs, receiveTimeNs), yawRad, pitchRad),
        )
        publishState(force = true)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!running || event.sensor.type != Sensor.TYPE_GYROSCOPE || event.values.size < 3) return
        val receiveTimeNs = SystemClock.elapsedRealtimeNanos()
        // 部分 ROM 的 SensorEvent.timestamp 与 elapsedRealtime 不在同一时钟域；
        // 偏差过大时改用接收时刻，避免样本被滑动窗口立刻裁掉。
        val sourceTimeNs =
            if (kotlin.math.abs(event.timestamp - receiveTimeNs) <= MAX_SENSOR_CLOCK_SKEW_NS) {
                event.timestamp
            } else {
                receiveTimeNs
            }
        engine.addGyro(
            GyroSample(
                stamp = monotonicStamp(sourceTimeNs, receiveTimeNs),
                velocity = AngularVelocity(event.values[0], event.values[1], event.values[2]),
            ),
        )
        publishState()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun touchStamp(uptimeMs: Long, receiveTimeNs: Long) =
        monotonicStamp(uptimeMs * NANOS_PER_MILLISECOND + touchClockOffsetNs, receiveTimeNs)

    private fun monotonicStamp(sourceTimeNs: Long, receiveTimeNs: Long) = EventStamp(
        eventTimeNs = sourceTimeNs,
        receiveTimeNs = receiveTimeNs,
        sessionId = sessionId,
        transformGeneration = generation,
    )

    private fun publishState(force: Boolean = false) {
        val nowNs = SystemClock.elapsedRealtimeNanos()
        if (!force && nowNs - lastUiPostNs < UI_UPDATE_INTERVAL_NS) return
        lastUiPostNs = nowNs
        val stats = engine.stats()
        val state = MotionDiagnosticsState(
            running = running,
            gyroscopeAvailable = gyroscope != null,
            stats = stats,
            report = lastReport,
        )
        mainHandler.post { stateListener(state) }
    }

    companion object {
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private const val UI_UPDATE_INTERVAL_NS = 100_000_000L
        private const val MAX_SENSOR_CLOCK_SKEW_NS = 2_000_000_000L
    }
}

data class MotionDiagnosticsState(
    val running: Boolean,
    val gyroscopeAvailable: Boolean,
    val stats: MotionBufferStats,
    val report: ConsistencyReport?,
)
