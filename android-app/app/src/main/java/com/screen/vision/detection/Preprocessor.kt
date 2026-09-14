package com.screen.vision.detection

import android.graphics.Bitmap
import android.graphics.Color
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor

/**
 * YOLO 输入预处理 (CONTRACTS C03):
 *   r = min(S/W, S/H); newW=max(1,floor(W*r)); newH=max(1,floor(H*r))
 *   居中 LetterBox，填充 114，逐轴记录实际比例 scaleX/scaleY
 *   RGB float32 NHWC，每通道 /255
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

        val scaled = Bitmap.createScaledBitmap(bitmap, newW, newH, true)
        val canvas = Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(canvas)
        c.drawColor(Color.rgb(114, 114, 114))
        c.drawBitmap(scaled, padX.toFloat(), padY.toFloat(), null)
        if (scaled !== bitmap) scaled.recycle()

        val pixels = IntArray(inputSize * inputSize)
        canvas.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        canvas.recycle()

        val inputBuffer = ByteBuffer.allocateDirect(inputSize * inputSize * 3 * 4)
        inputBuffer.order(ByteOrder.nativeOrder())
        for (pixel in pixels) {
            inputBuffer.putFloat(((pixel shr 16) and 0xFF) / 255.0f)
            inputBuffer.putFloat(((pixel shr 8) and 0xFF) / 255.0f)
            inputBuffer.putFloat((pixel and 0xFF) / 255.0f)
        }
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
}
