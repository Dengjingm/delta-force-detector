package com.screen.vision.detection

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * YOLOv8 TFLite 推理引擎 (XNNPACK CPU)。
 *
 * 模型契约 (CONTRACTS C02):
 *   - 输入  [1, S, S, 3] float32 RGB，归一化 0..1
 *   - 输出  [1, 300, 6] float32，布局 xyxy_score_class，坐标归一化到 LetterBox 画布
 *   - nms 已内置，无 objectness；类别固定为 enemy
 *
 * 输出维度在加载时校验，不靠 shape[1]-5 猜类别数；推理用容量/dtype 匹配的直接 ByteBuffer。
 */
class YOLODetector(
    context: Context,
    modelPath: String = "model.tflite",
    private val numThreads: Int = 4,
) : AutoCloseable {

    private var interpreter: Interpreter
    val inputSize: Int
    val numDetections: Int
    val outputCols: Int

    init {
        val model = loadModelFile(context, modelPath)
        val options = Interpreter.Options().apply {
            setNumThreads(numThreads)
        }
        interpreter = Interpreter(model, options)

        val inputShape = interpreter.getInputTensor(0).shape()
        require(inputShape.size == 4 && inputShape[0] == 1 &&
            inputShape[1] == inputShape[2] && inputShape[3] == 3) {
            "Unexpected input tensor shape: ${inputShape.toList()}, expected [1,S,S,3]"
        }
        inputSize = inputShape[1]

        val outputShape = interpreter.getOutputTensor(0).shape()
        require(outputShape.size == 3 && outputShape[0] == 1 && outputShape[2] == 6) {
            "Unexpected output tensor shape: ${outputShape.toList()}, expected [1,N,6]"
        }
        numDetections = outputShape[1]
        outputCols = outputShape[2]
    }

    fun detect(inputBuffer: ByteBuffer): FloatArray {
        val numElements = numDetections * outputCols
        val output = ByteBuffer.allocateDirect(numElements * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
        interpreter.run(inputBuffer, output)

        val result = FloatArray(numElements)
        output.rewind()
        output.asFloatBuffer().get(result)
        return result
    }

    private fun loadModelFile(context: Context, modelPath: String): MappedByteBuffer {
        return context.assets.openFd(modelPath).use { fd ->
            FileInputStream(fd.fileDescriptor).channel.map(
                FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength
            )
        }.apply { order(ByteOrder.nativeOrder()) }
    }

    override fun close() { interpreter.close() }
}
