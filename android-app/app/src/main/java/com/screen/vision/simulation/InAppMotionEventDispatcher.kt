package com.screen.vision.simulation

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View

/** Replays a generated trace in real time only to a caller-provided View in this app process. */
class InAppMotionEventDispatcher(
    private val handler: Handler = Handler(Looper.getMainLooper()),
) {
    fun play(
        target: View,
        samples: List<SimulatedTouchSample>,
        onComplete: (allEventsAccepted: Boolean) -> Unit = {},
    ): InAppPlaybackHandle {
        if (samples.isEmpty()) {
            handler.post { onComplete(true) }
            return InAppPlaybackHandle(handler, emptyList())
        }
        require(samples.zipWithNext().all { (a, b) -> b.timeNs > a.timeNs })
        require(samples.first().phase == SimulatedTouchPhase.DOWN)
        require(samples.last().phase == SimulatedTouchPhase.UP)
        val baseUptimeMs = SystemClock.uptimeMillis()
        val firstTimeNs = samples.first().timeNs
        val downTimeMs = baseUptimeMs
        val acceptance = booleanArrayOf(true)
        val tasks = samples.mapIndexed { index, sample ->
            val offsetMs = (sample.timeNs - firstTimeNs) / NANOS_PER_MILLISECOND
            Runnable {
                acceptance[0] = dispatchOne(target, sample, downTimeMs, baseUptimeMs + offsetMs) && acceptance[0]
                if (index == samples.lastIndex) onComplete(acceptance[0])
            }.also { handler.postAtTime(it, baseUptimeMs + offsetMs) }
        }
        return InAppPlaybackHandle(handler, tasks)
    }

    private fun dispatchOne(
        target: View,
        sample: SimulatedTouchSample,
        downTimeMs: Long,
        eventTimeMs: Long,
    ): Boolean {
            val properties = MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
            val coordinates = MotionEvent.PointerCoords().apply {
                x = sample.xPx
                y = sample.yPx
                pressure = sample.pressure
                size = sample.size
                touchMajor = sample.touchMajorPx
                touchMinor = sample.touchMinorPx
            }
            val event = MotionEvent.obtain(
                downTimeMs,
                eventTimeMs,
                action(sample.phase),
                1,
                arrayOf(properties),
                arrayOf(coordinates),
                0,
                0,
                1f,
                1f,
                0,
                0,
                InputDevice.SOURCE_TOUCHSCREEN,
                0,
            )
            try {
                return target.dispatchTouchEvent(event)
            } finally {
                event.recycle()
            }
    }

    private fun action(phase: SimulatedTouchPhase): Int = when (phase) {
        SimulatedTouchPhase.DOWN -> MotionEvent.ACTION_DOWN
        SimulatedTouchPhase.MOVE -> MotionEvent.ACTION_MOVE
        SimulatedTouchPhase.UP -> MotionEvent.ACTION_UP
    }

    companion object {
        private const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}

class InAppPlaybackHandle internal constructor(
    private val handler: Handler,
    private val tasks: List<Runnable>,
) {
    fun cancel() {
        tasks.forEach(handler::removeCallbacks)
    }
}
