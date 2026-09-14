package com.screen.vision.model

/**
 * 单次检测结果
 */
data class DetectResult(
    val elementId: String,   // 类别名，当前为 "enemy"
    val x: Int,              // 目标中心 x (屏幕像素坐标)
    val y: Int,              // 目标中心 y (屏幕像素坐标)
    val confidence: Float,   // 置信度 0.0 ~ 1.0
    val x1: Float,           // 目标框左上 x (原图像素坐标)
    val y1: Float,           // 目标框左上 y (原图像素坐标)
    val x2: Float,           // 目标框右下 x (原图像素坐标)
    val y2: Float,           // 目标框右下 y (原图像素坐标)
) {
    companion object {
        fun isValid(result: DetectResult, screenWidth: Int, screenHeight: Int): Boolean {
            return result.x in 0..screenWidth && result.y in 0..screenHeight
        }
    }
}