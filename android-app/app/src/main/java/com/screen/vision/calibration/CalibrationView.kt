package com.screen.vision.calibration

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import com.screen.vision.model.DetectResult
import kotlin.math.min

/**
 * 校准覆盖层: 按源分辨率 (采集帧) 等比缩放到视图, 绘制屏幕中心十字、
 * 检测框、离中心最近的锁定目标与中心→目标的注入向量。
 */
class CalibrationView(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var detections: List<DetectResult> = emptyList()
    private var sourceWidth = 1
    private var sourceHeight = 1

    fun setSourceSize(width: Int, height: Int) {
        sourceWidth = if (width > 0) width else 1
        sourceHeight = if (height > 0) height else 1
        invalidate()
    }

    fun setDetections(list: List<DetectResult>) {
        detections = list
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val scale = min(width.toFloat() / sourceWidth, height.toFloat() / sourceHeight)
        val ox = (width - sourceWidth * scale) / 2f
        val oy = (height - sourceHeight * scale) / 2f

        fun sx(px: Float) = ox + px * scale
        fun sy(py: Float) = oy + py * scale

        val cx = sx(sourceWidth / 2f)
        val cy = sy(sourceHeight / 2f)

        paint.style = Paint.Style.STROKE
        paint.color = Color.GREEN
        paint.strokeWidth = 2f
        canvas.drawLine(cx - 24, cy, cx + 24, cy, paint)
        canvas.drawLine(cx, cy - 24, cx, cy + 24, paint)

        val locked = detections.minByOrNull { d ->
            val dx = (d.x - sourceWidth / 2).toLong()
            val dy = (d.y - sourceHeight / 2).toLong()
            dx * dx + dy * dy
        }

        for (d in detections) {
            paint.color = if (d === locked) Color.RED else Color.YELLOW
            paint.strokeWidth = 2f
            canvas.drawRect(sx(d.x1), sy(d.y1), sx(d.x2), sy(d.y2), paint)
        }

        locked?.let {
            paint.color = Color.CYAN
            paint.strokeWidth = 3f
            canvas.drawLine(cx, cy, sx(it.x.toFloat()), sy(it.y.toFloat()), paint)

            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            canvas.drawCircle(sx(it.x.toFloat()), sy(it.y.toFloat()), 6f, paint)
        }
    }
}
