package com.screen.vision.detection

import android.util.Log
import com.screen.vision.detection.Preprocessor.Preprocessed
import com.screen.vision.model.DetectResult
import kotlin.math.max
import kotlin.math.min

/**
 * YOLO 输出后处理 — NMS + 坐标映射。
 *
 * classNames 由调用方传入，与训练时的 dataset.yaml 保持一致的顺序。
 */
class PostProcessor(
    private val classNames: List<String>,
    private val confidenceThreshold: Float = 0.45f,
    private val nmsThreshold: Float = 0.5f,
) {

    data class BoundingBox(
        val x1: Float, val y1: Float, val x2: Float, val y2: Float,
        val confidence: Float,
        val classId: Int,
    )

    fun process(rawOutput: Array<FloatArray>, preproc: Preprocessed): List<DetectResult> {
        val bboxes = parseOutput(rawOutput)
        val kept = nonMaxSuppression(bboxes)
        return kept.map { mapToOriginal(it, preproc) }
    }

    private fun parseOutput(output: Array<FloatArray>): MutableList<BoundingBox> {
        val boxes = mutableListOf<BoundingBox>()
        val row = output[0]
        val numClasses = classNames.size
        val channelsPerDet = 4 + 1 + numClasses
        val numDetections = row.size / channelsPerDet

        for (i in 0 until numDetections) {
            val offset = i * channelsPerDet
            val cx = row[offset]
            val cy = row[offset + 1]
            val w = row[offset + 2]
            val h = row[offset + 3]
            if (w <= 0 || h <= 0) continue

            var maxConf = 0f
            var maxClassId = -1
            for (c in 0 until numClasses) {
                val conf = row[offset + 5 + c]
                if (conf > maxConf) { maxConf = conf; maxClassId = c }
            }
            if (maxConf >= confidenceThreshold && maxClassId >= 0) {
                boxes.add(BoundingBox(
                    x1 = cx - w / 2, y1 = cy - h / 2,
                    x2 = cx + w / 2, y2 = cy + h / 2,
                    confidence = maxConf, classId = maxClassId,
                ))
            }
        }
        return boxes
    }

    private fun nonMaxSuppression(boxes: List<BoundingBox>): List<BoundingBox> {
        if (boxes.isEmpty()) return emptyList()
        val sorted = boxes.sortedByDescending { it.confidence }
        val kept = mutableListOf<BoundingBox>()
        val picked = BooleanArray(sorted.size)

        for (i in sorted.indices) {
            if (picked[i]) continue
            kept.add(sorted[i])
            for (j in i + 1 until sorted.size) {
                if (picked[j]) continue
                if (sorted[i].classId != sorted[j].classId) continue
                if (computeIoU(sorted[i], sorted[j]) > nmsThreshold) picked[j] = true
            }
        }
        return kept
    }

    private fun computeIoU(a: BoundingBox, b: BoundingBox): Float {
        val x1 = max(a.x1, b.x1); val y1 = max(a.y1, b.y1)
        val x2 = min(a.x2, b.x2); val y2 = min(a.y2, b.y2)
        val inter = max(0f, x2 - x1) * max(0f, y2 - y1)
        val union = (a.x2 - a.x1) * (a.y2 - a.y1) + (b.x2 - b.x1) * (b.y2 - b.y1) - inter
        return if (union <= 0f) 0f else inter / union
    }

    private fun mapToOriginal(box: BoundingBox, preproc: Preprocessed): DetectResult {
        val cx = ((box.x1 + box.x2) / 2 - preproc.padX) / preproc.scaleX
        val cy = ((box.y1 + box.y2) / 2 - preproc.padY) / preproc.scaleY
        return DetectResult(
            elementId = classNames.getOrElse(box.classId) { "class_${box.classId}" },
            x = cx.coerceIn(0f, preproc.originalWidth.toFloat()).toInt(),
            y = cy.coerceIn(0f, preproc.originalHeight.toFloat()).toInt(),
            confidence = box.confidence,
        )
    }
}