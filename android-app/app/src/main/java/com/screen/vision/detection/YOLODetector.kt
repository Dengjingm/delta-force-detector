package com.screen.vision.detection

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * YOLOv8nano TFLite 推理引擎。
 *
 * 优先 GPU Delegate，回退 XNNPACK CPU。
 * numClasses 从模型输出 tensor shape 自动推导。
 */
class YOLODetector(
    context: Context,
    modelPath: String = "model.tflite",
    private val numThreads: Int = 4,
    private val classNames: List<String> = emptyList(),
) : AutoCloseable {

    private var interpreter: Interpreter
    val inputSize: Int
    val numClasses: Int

    init {
        val model = loadModelFile(context, modelPath)
        val options = Interpreter.Options().apply {
            setNumThreads(numThreads)
            if (CompatibilityList().isDelegateSupportedOnThisDevice) {
                try {
                    val gpuOptions = GpuDelegate.Options().apply {
                        inferencePreference = GpuDelegate.Options.INFERENCE_PREFERENCE_SUSTAINED_SPEED
                    }
                    addDelegate(GpuDelegate(gpuOptions))
                    Log.i(TAG, "GPU Delegate enabled")
                } catch (e: Exception) {
                    Log.w(TAG, "GPU Delegate fallback to CPU: ${e.message}")
                }
            }
        }
        interpreter = Interpreter(model, options)

        val inputShape = interpreter.getInputTensor(0).shape()
        inputSize = inputShape[1]  // 640

        val outputShape = interpreter.getOutputTensor(0).shape()
        // YOLOv8 output: [1, 4+1+numClasses, numDetections]
        numClasses = outputShape[1] - 5

        if (classNames.isNotEmpty() && classNames.size != numClasses) {
            Log.w(TAG, "classNames size (${classNames.size}) != numClasses ($numClasses)")
        }
    }

    fun detect(inputBuffer: ByteBuffer): Array<FloatArray> {
        val outputSize = numClasses + 5  // bbox(4) + conf(1) + class probs(N)
        val output = Array(1) { FloatArray(interpreter.getOutputTensor(0).shape().let {
            it[1] * it[2]
        })}
        interpreter.run(inputBuffer, output)
        return output
    }

    private fun loadModelFile(context: Context, modelPath: String): MappedByteBuffer {
        return context.assets.openFd(modelPath).use { fd ->
            FileInputStream(fd.fileDescriptor).channel.map(
                FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength
            )
        }.apply { order(ByteOrder.nativeOrder()) }
    }

    override fun close() { interpreter.close() }

    companion object {
        private const val TAG = "YOLODetector"
    }
}