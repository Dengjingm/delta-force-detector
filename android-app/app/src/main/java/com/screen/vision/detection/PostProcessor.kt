package com.screen.vision.detection

import android.util.Log
import com.screen.vision.detection.Preprocessor.Preprocessed
import com.screen.vision.model.DetectResult
import kotlin.math.abs
import kotlin.math.floor

/**
 * YOLO 输出后处理 — 解析内置 NMS 的归一化输出并映射回原图中心坐标。
 *
 * 模型输出 (CONTRACTS C02) 每条记录 6 个 float: x1,y1,x2,y2,score,classId，
 * 坐标归一化到含 LetterBox 的输入画布；classId=0 为 enemy；无 objectness，
 * 不再执行第二次 NMS。
 */
class PostProcessor(
    private val classNames: List<String>,
    private val confidenceThreshold: Float = 0.45f,
) {

    private val cols = 6
    private val classTolerance = 1e-4f

    fun process(rawOutput: FloatArray, preproc: Preprocessed): List<DetectResult> {
        val results = mutableListOf<DetectResult>()
        val canvas = preproc.inputSize.toFloat()
        val validLeft = preproc.padX.toFloat()
        val validTop = preproc.padY.toFloat()
        val validRight = validLeft + preproc.newW
        val validBottom = validTop + preproc.newH

        var i = 0
        while (i + cols <= rawOutput.size) {
            val x1n = rawOutput[i]
            val y1n = rawOutput[i + 1]
            val x2n = rawOutput[i + 2]
            val y2n = rawOutput[i + 3]
            val score = rawOutput[i + 4]
            val classId = rawOutput[i + 5]
            i += cols

            // 低分/零分 padding 记录跳过
            if (score <= 0f) continue

            // 非有限或越界的非 padding 记录视为坏输出，本次帧失败
            if (!score.isFinite() || score < 0f || score > 1f ||
                !classId.isFinite() || abs(classId - 0f) > classTolerance
            ) {
                Log.e(TAG, "Bad output record at ${i - cols}: score=$score classId=$classId")
                return emptyList()
            }
            if (score < confidenceThreshold) continue

            val x1 = x1n * canvas
            val y1 = y1n * canvas
            val x2 = x2n * canvas
            val y2 = y2n * canvas
            if (x2 <= x1 || y2 <= y1) continue

            // 中心位于纯 padding 区域的记录丢弃
            val cxCanvas = (x1 + x2) / 2f
            val cyCanvas = (y1 + y2) / 2f
            if (cxCanvas < validLeft || cxCanvas > validRight ||
                cyCanvas < validTop || cyCanvas > validBottom
            ) continue

            val cx = (cxCanvas - validLeft) / preproc.scaleX
            val cy = (cyCanvas - validTop) / preproc.scaleY
            val centerX = floor(cx).coerceIn(0f, (preproc.originalWidth - 1).toFloat()).toInt()
            val centerY = floor(cy).coerceIn(0f, (preproc.originalHeight - 1).toFloat()).toInt()

            val ox1 = ((x1 - validLeft) / preproc.scaleX).coerceIn(0f, preproc.originalWidth.toFloat())
            val oy1 = ((y1 - validTop) / preproc.scaleY).coerceIn(0f, preproc.originalHeight.toFloat())
            val ox2 = ((x2 - validLeft) / preproc.scaleX).coerceIn(0f, preproc.originalWidth.toFloat())
            val oy2 = ((y2 - validTop) / preproc.scaleY).coerceIn(0f, preproc.originalHeight.toFloat())

            results.add(DetectResult(
                elementId = classNames.getOrElse(classId.toInt()) { "class_${classId.toInt()}" },
                x = centerX,
                y = centerY,
                confidence = score,
                x1 = ox1,
                y1 = oy1,
                x2 = ox2,
                y2 = oy2,
            ))
        }
        return results
    }

    companion object {
        private const val TAG = "PostProcessor"
    }
}
