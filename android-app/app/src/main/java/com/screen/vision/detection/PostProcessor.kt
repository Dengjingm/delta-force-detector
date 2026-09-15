package com.screen.vision.detection

import android.util.Log
import com.screen.vision.detection.Preprocessor.Preprocessed
import com.screen.vision.detection.YOLODetector.OutputLayout
import com.screen.vision.model.DetectResult
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * YOLO 输出后处理 — 映射回原图中心坐标。
 *
 * 支持 C02 内置 NMS 的 [1,N,6] xyxy_score_class，以及当前导出的无 NMS
 * [1,5,A]/[1,A,5] cxcywh+score（画布归一化 0–1）。后者在端上做 greedy NMS。
 */
class PostProcessor(
    private val classNames: List<String>,
    private val confidenceThreshold: Float = 0.45f,
    private val outputLayout: OutputLayout = OutputLayout.NMS_XYXY,
    private val iouThreshold: Float = 0.5f,
    private val maxDetections: Int = 300,
) {

    private val cols = 6
    private val classTolerance = 1e-4f

    fun process(rawOutput: FloatArray, preproc: Preprocessed): List<DetectResult> {
        return when (outputLayout) {
            OutputLayout.NMS_XYXY -> processNmsRecords(rawOutput, preproc)
            OutputLayout.RAW_CHW, OutputLayout.RAW_HWC -> processRaw(rawOutput, preproc)
        }
    }

    private fun processNmsRecords(rawOutput: FloatArray, preproc: Preprocessed): List<DetectResult> {
        val results = mutableListOf<DetectResult>()
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

            val canvas = preproc.inputSize.toFloat()
            val x1 = x1n * canvas
            val y1 = y1n * canvas
            val x2 = x2n * canvas
            val y2 = y2n * canvas
            if (x2 <= x1 || y2 <= y1) continue
            toDetectResult(preproc, x1, y1, x2, y2, score, classId.toInt())?.let(results::add)
        }
        return results
    }

    private fun processRaw(rawOutput: FloatArray, preproc: Preprocessed): List<DetectResult> {
        if (rawOutput.size < 5 || rawOutput.size % 5 != 0) {
            Log.e(TAG, "Raw output size ${rawOutput.size} is not a multiple of 5")
            return emptyList()
        }
        val anchors = rawOutput.size / 5
        val canvas = preproc.inputSize.toFloat()
        val candidates = ArrayList<FloatArray>(64)
        for (a in 0 until anchors) {
            val score = rawAt(rawOutput, anchors, 4, a)
            if (!score.isFinite() || score < confidenceThreshold) continue
            val cx = rawAt(rawOutput, anchors, 0, a) * canvas
            val cy = rawAt(rawOutput, anchors, 1, a) * canvas
            val bw = rawAt(rawOutput, anchors, 2, a) * canvas
            val bh = rawAt(rawOutput, anchors, 3, a) * canvas
            if (!cx.isFinite() || !cy.isFinite() || !bw.isFinite() || !bh.isFinite()) continue
            if (bw <= 0f || bh <= 0f) continue
            val x1 = cx - bw / 2f
            val y1 = cy - bh / 2f
            val x2 = cx + bw / 2f
            val y2 = cy + bh / 2f
            if (x2 <= x1 || y2 <= y1) continue
            candidates.add(floatArrayOf(x1, y1, x2, y2, score))
        }
        candidates.sortByDescending { it[4] }
        val kept = greedyNms(candidates)
        return kept.mapNotNull { box ->
            toDetectResult(preproc, box[0], box[1], box[2], box[3], box[4], 0)
        }
    }

    private fun rawAt(raw: FloatArray, anchors: Int, channel: Int, anchor: Int): Float {
        return if (outputLayout == OutputLayout.RAW_CHW) {
            raw[channel * anchors + anchor]
        } else {
            raw[anchor * 5 + channel]
        }
    }

    private fun greedyNms(boxes: List<FloatArray>): List<FloatArray> {
        val selected = ArrayList<FloatArray>(min(maxDetections, boxes.size))
        val suppressed = BooleanArray(boxes.size)
        for (i in boxes.indices) {
            if (suppressed[i]) continue
            val a = boxes[i]
            selected.add(a)
            if (selected.size >= maxDetections) break
            for (j in i + 1 until boxes.size) {
                if (suppressed[j]) continue
                if (iou(a, boxes[j]) > iouThreshold) suppressed[j] = true
            }
        }
        return selected
    }

    private fun iou(a: FloatArray, b: FloatArray): Float {
        val ix1 = max(a[0], b[0])
        val iy1 = max(a[1], b[1])
        val ix2 = min(a[2], b[2])
        val iy2 = min(a[3], b[3])
        val iw = max(0f, ix2 - ix1)
        val ih = max(0f, iy2 - iy1)
        val inter = iw * ih
        val areaA = max(0f, a[2] - a[0]) * max(0f, a[3] - a[1])
        val areaB = max(0f, b[2] - b[0]) * max(0f, b[3] - b[1])
        val union = areaA + areaB - inter
        return if (union <= 0f) 0f else inter / union
    }

    private fun toDetectResult(
        preproc: Preprocessed,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        score: Float,
        classId: Int,
    ): DetectResult? {
        val validLeft = preproc.padX.toFloat()
        val validTop = preproc.padY.toFloat()
        val validRight = validLeft + preproc.newW
        val validBottom = validTop + preproc.newH
        val cxCanvas = (x1 + x2) / 2f
        val cyCanvas = (y1 + y2) / 2f
        if (cxCanvas < validLeft || cxCanvas > validRight ||
            cyCanvas < validTop || cyCanvas > validBottom
        ) return null

        val cx = (cxCanvas - validLeft) / preproc.scaleX
        val cy = (cyCanvas - validTop) / preproc.scaleY
        val centerX = floor(cx).coerceIn(0f, (preproc.originalWidth - 1).toFloat()).toInt()
        val centerY = floor(cy).coerceIn(0f, (preproc.originalHeight - 1).toFloat()).toInt()
        val ox1 = ((x1 - validLeft) / preproc.scaleX).coerceIn(0f, preproc.originalWidth.toFloat())
        val oy1 = ((y1 - validTop) / preproc.scaleY).coerceIn(0f, preproc.originalHeight.toFloat())
        val ox2 = ((x2 - validLeft) / preproc.scaleX).coerceIn(0f, preproc.originalWidth.toFloat())
        val oy2 = ((y2 - validTop) / preproc.scaleY).coerceIn(0f, preproc.originalHeight.toFloat())
        return DetectResult(
            elementId = classNames.getOrElse(classId) { "class_$classId" },
            x = centerX,
            y = centerY,
            confidence = score,
            x1 = ox1,
            y1 = oy1,
            x2 = ox2,
            y2 = oy2,
        )
    }

    companion object {
        private const val TAG = "PostProcessor"
    }
}
