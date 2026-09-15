package com.screen.vision.testbench

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.screen.vision.model.DetectResult
import kotlin.math.min

/**
 * 图片测试画布：原图 + 检测框；平移模拟镜头，中心十字对准锁定目标。
 */
class ImageTestView(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val srcRect = RectF()
    private val dstRect = RectF()

    private var bitmap: Bitmap? = null
    private var detections: List<DetectResult> = emptyList()
    private var hasReticle = false
    private var panX = 0
    private var panY = 0

    fun setImage(image: Bitmap?) {
        bitmap = image
        detections = emptyList()
        hasReticle = false
        panX = 0
        panY = 0
        invalidate()
    }

    fun setDetections(list: List<DetectResult>) {
        detections = list
        invalidate()
    }

    fun setReticleVisible(visible: Boolean) {
        hasReticle = visible
        invalidate()
    }

    /** 模拟镜头平移：内容向反方向滑，屏幕中心十字保持不动。 */
    fun setPan(x: Int, y: Int) {
        panX = x
        panY = y
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(12, 12, 12))

        val image = bitmap ?: return
        val scale = min(width.toFloat() / image.width, height.toFloat() / image.height)
        val dw = image.width * scale
        val dh = image.height * scale
        val ox = (width - dw) / 2f - panX * scale
        val oy = (height - dh) / 2f - panY * scale
        dstRect.set(ox, oy, ox + dw, oy + dh)
        srcRect.set(0f, 0f, image.width.toFloat(), image.height.toFloat())
        canvas.drawBitmap(image, null, dstRect, paint)

        fun sx(px: Float) = ox + px * scale
        fun sy(py: Float) = oy + py * scale

        val cx = width / 2f
        val cy = height / 2f

        paint.style = Paint.Style.STROKE
        paint.color = Color.GREEN
        paint.strokeWidth = 2f
        canvas.drawLine(cx - 18, cy, cx + 18, cy, paint)
        canvas.drawLine(cx, cy - 18, cx, cy + 18, paint)

        val lockX = image.width / 2 + panX
        val lockY = image.height / 2 + panY
        val locked = detections.minByOrNull { d ->
            val dx = (d.x - lockX).toLong()
            val dy = (d.y - lockY).toLong()
            dx * dx + dy * dy
        }

        for (d in detections) {
            paint.style = Paint.Style.STROKE
            paint.color = if (d === locked) Color.RED else Color.YELLOW
            paint.strokeWidth = 3f
            canvas.drawRect(sx(d.x1), sy(d.y1), sx(d.x2), sy(d.y2), paint)
            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            paint.textSize = 28f
            canvas.drawText("%.2f".format(d.confidence), sx(d.x1), sy(d.y1) - 8f, paint)
        }

        if (hasReticle) {
            paint.style = Paint.Style.STROKE
            paint.color = Color.CYAN
            paint.strokeWidth = 4f
            canvas.drawCircle(cx, cy, 22f, paint)
        }
    }
}
