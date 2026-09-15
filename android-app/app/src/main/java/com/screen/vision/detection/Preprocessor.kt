package com.screen.vision.detection

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor

/**
 * YOLO 输入预处理 (CONTRACTS C03):
 *   r = min(S/W, S/H); newW=max(1,floor(W*r)); newH=max(1,floor(H*r))
 *   居中 LetterBox，填充 114，逐轴记录实际比例 scaleX/scaleY
 *   RGB float32 NHWC，每通道 /255
 *
 * 画布、像素数组和输入 ByteBuffer 在实例内复用，避免每帧数 MB 分配。
 */
class Preprocessor(
    private val inputSize: Int = 640,
) {

    data class Preprocessed(
        val inputBuffer: ByteBuffer,
        val scaleX: Float,
        val scaleY: Float,
        val padX: Int,        // left
        val padY: Int,        // top
        val newW: Int,        // 缩放到画布中的有效宽
        val newH: Int,        // 缩放到画布中的有效高
        val inputSize: Int,   // S
        val originalWidth: Int,
        val originalHeight: Int,
    )

    private val canvasBitmap: Bitmap =
        Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(canvasBitmap)
    private val pixels = IntArray(inputSize * inputSize)
    private val rgb = FloatArray(inputSize * inputSize * 3)
    private val inputBuffer: ByteBuffer =
        ByteBuffer.allocateDirect(inputSize * inputSize * 3 * 4).order(ByteOrder.nativeOrder())
    private val filterPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val srcRect = Rect()
    private val dstRect = Rect()
    private var scaled: Bitmap? = null

    fun preprocess(bitmap: Bitmap): Preprocessed {
        val origW = bitmap.width
        val origH = bitmap.height
        val r = minOf(inputSize.toFloat() / origW, inputSize.toFloat() / origH)
        val newW = maxOf(1, floor(origW * r).toInt())
        val newH = maxOf(1, floor(origH * r).toInt())
        val padX = floor((inputSize - newW) / 2.0).toInt()
        val padY = floor((inputSize - newH) / 2.0).toInt()
        val scaleX = newW.toFloat() / origW
        val scaleY = newH.toFloat() / origH

        canvas.drawColor(Color.rgb(114, 114, 114))
        if (newW == origW && newH == origH) {
            canvas.drawBitmap(bitmap, padX.toFloat(), padY.toFloat(), null)
        } else {
            val resized = ensureScaled(newW, newH)
            srcRect.set(0, 0, origW, origH)
            dstRect.set(0, 0, newW, newH)
            Canvas(resized).drawBitmap(bitmap, srcRect, dstRect, filterPaint)
            canvas.drawBitmap(resized, padX.toFloat(), padY.toFloat(), null)
        }

        canvasBitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        var o = 0
        for (pixel in pixels) {
            rgb[o++] = ((pixel shr 16) and 0xFF) / 255.0f
            rgb[o++] = ((pixel shr 8) and 0xFF) / 255.0f
            rgb[o++] = (pixel and 0xFF) / 255.0f
        }
        inputBuffer.rewind()
        inputBuffer.asFloatBuffer().put(rgb)
        inputBuffer.rewind()

        return Preprocessed(
            inputBuffer = inputBuffer,
            scaleX = scaleX,
            scaleY = scaleY,
            padX = padX,
            padY = padY,
            newW = newW,
            newH = newH,
            inputSize = inputSize,
            originalWidth = origW,
            originalHeight = origH,
        )
    }

    private fun ensureScaled(width: Int, height: Int): Bitmap {
        val current = scaled
        if (current != null && current.width == width && current.height == height && !current.isRecycled) {
            return current
        }
        current?.recycle()
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { scaled = it }
    }
}
