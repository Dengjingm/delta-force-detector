package com.screen.vision.detection

import android.graphics.Bitmap
import android.graphics.Color
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * YOLO 输入预处理：
 * Bitmap → 模型输入尺寸缩放 → normalized float32 ByteBuffer (RGB)
 */
class Preprocessor(
    private val inputSize: Int = 640,
) {

    data class Preprocessed(
        val inputBuffer: ByteBuffer,
        val scaleX: Float,
        val scaleY: Float,
        val padX: Int,
        val padY: Int,
        val originalWidth: Int,
        val originalHeight: Int,
    )

    fun preprocess(bitmap: Bitmap): Preprocessed {
        val origW = bitmap.width
        val origH = bitmap.height
        val scale = minOf(inputSize.toFloat() / origW, inputSize.toFloat() / origH)
        val newW = (origW * scale).toInt()
        val newH = (origH * scale).toInt()
        val padX = (inputSize - newW) / 2
        val padY = (inputSize - newH) / 2

        val scaled = Bitmap.createScaledBitmap(bitmap, newW, newH, true)
        val canvas = Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(canvas)
        c.drawColor(Color.rgb(114, 114, 114))
        c.drawBitmap(scaled, padX.toFloat(), padY.toFloat(), null)
        if (scaled !== bitmap) scaled.recycle()

        val pixels = IntArray(inputSize * inputSize)
        canvas.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        canvas.recycle()

        val inputBuffer = ByteBuffer.allocateDirect(1 * inputSize * inputSize * 3 * 4)
        inputBuffer.order(ByteOrder.nativeOrder())
        inputBuffer.rewind()
        for (pixel in pixels) {
            inputBuffer.putFloat(((pixel shr 16) and 0xFF) / 255.0f)
            inputBuffer.putFloat(((pixel shr 8) and 0xFF) / 255.0f)
            inputBuffer.putFloat((pixel and 0xFF) / 255.0f)
        }
        inputBuffer.rewind()

        return Preprocessed(
            inputBuffer = inputBuffer,
            scaleX = scale, scaleY = scale,
            padX = padX, padY = padY,
            originalWidth = origW, originalHeight = origH,
        )
    }
}