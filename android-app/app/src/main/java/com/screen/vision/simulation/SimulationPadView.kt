package com.screen.vision.simulation

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View

/** Small application-owned surface used to verify generated MotionEvent fields and timing. */
class SimulationPadView(context: Context) : View(context) {
    private val path = Path()
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(70, 180, 255)
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }
    private val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 190, 70)
        style = Paint.Style.FILL
    }
    private var markerX = 0f
    private var markerY = 0f
    private var markerRadius = 0f

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.rgb(30, 34, 40))
        canvas.drawPath(path, stroke)
        if (markerRadius > 0f) canvas.drawCircle(markerX, markerY, markerRadius, marker)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                path.reset()
                path.moveTo(event.x, event.y)
            }
            MotionEvent.ACTION_MOVE -> path.lineTo(event.x, event.y)
            MotionEvent.ACTION_UP -> path.lineTo(event.x, event.y)
            MotionEvent.ACTION_CANCEL -> return true
            else -> return false
        }
        markerX = event.x
        markerY = event.y
        markerRadius = (event.touchMajor * 0.5f).coerceAtLeast(4f)
        invalidate()
        return true
    }
}
