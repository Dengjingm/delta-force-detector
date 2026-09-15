package com.screen.vision.detection

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * YOLOv8 TFLite 推理引擎。
 *
 * 默认 AUTO：在专用线程上尝试 GPU delegate，失败则同线程回退 XNNPACK CPU。
 * GPU 的创建、warmup、invoke、关闭都在该线程完成。
 *
 * 输入必须是 [1, S, S, 3] float32 RGB。输出兼容两种布局：
 *   - C02 内置 NMS：[1, N, 6] xyxy_score_class
 *   - 当前 Ultralytics 无 NMS 导出：[1, 5, A] 或 [1, A, 5]，cxcywh+score，0–1 归一化
 */
class YOLODetector(
    context: Context,
    modelPath: String = "model.tflite",
    private val numThreads: Int = 4,
) : AutoCloseable {

    enum class OutputLayout {
        /** [1, N, 6] 已 NMS 的 xyxy_score_class */
        NMS_XYXY,
        /** [1, 5, A] 通道在前：cx,cy,w,h,score */
        RAW_CHW,
        /** [1, A, 5] 通道在后 */
        RAW_HWC,
    }

    enum class Backend { GPU, CPU }

    private val model: MappedByteBuffer = loadModelFile(context, modelPath)
    private val thread = HandlerThread("tflite-infer").apply { start() }
    private val handler = Handler(thread.looper)
    private val closed = AtomicBoolean(false)

    private lateinit var interpreter: Interpreter
    private var gpuDelegate: GpuDelegate? = null
    private lateinit var outputBuffer: ByteBuffer
    private lateinit var outputArray: FloatArray

    val inputSize: Int
    val outputLayout: OutputLayout
    val numDetections: Int
    val outputCols: Int
    @Volatile var backend: Backend
        private set
    private val outputElements: Int

    init {
        val ready = CountDownLatch(1)
        var initError: Throwable? = null
        var loaded: Loaded? = null
        handler.post {
            try {
                loaded = createInterpreter(preferGpu = true)
            } catch (t: Throwable) {
                initError = t
            } finally {
                ready.countDown()
            }
        }
        if (!ready.await(20, TimeUnit.SECONDS)) {
            thread.quitSafely()
            error("TFLite init timed out")
        }
        initError?.let {
            thread.quitSafely()
            throw it
        }
        val state = loaded ?: error("TFLite init produced no interpreter")
        interpreter = state.interpreter
        gpuDelegate = state.delegate
        backend = state.backend
        inputSize = state.inputSize
        outputLayout = state.outputLayout
        numDetections = state.numDetections
        outputCols = state.outputCols
        outputElements = numDetections * outputCols
        outputBuffer = ByteBuffer.allocateDirect(outputElements * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
        outputArray = FloatArray(outputElements)
        Log.i(TAG, "Interpreter ready backend=$backend input=$inputSize layout=$outputLayout")
    }

    fun detect(inputBuffer: ByteBuffer): FloatArray {
        check(!closed.get()) { "YOLODetector is closed" }
        val done = CountDownLatch(1)
        var result: FloatArray? = null
        var error: Throwable? = null
        val posted = handler.post {
            try {
                result = runOnInferenceThread(inputBuffer)
            } catch (t: Throwable) {
                error = t
            } finally {
                done.countDown()
            }
        }
        if (!posted) error("inference thread is not running")
        if (!done.await(5, TimeUnit.SECONDS)) error("TFLite detect timed out")
        error?.let { throw it }
        return result ?: error("TFLite detect returned no output")
    }

    private fun runOnInferenceThread(inputBuffer: ByteBuffer): FloatArray {
        try {
            return invoke(inputBuffer)
        } catch (t: Throwable) {
            if (backend == Backend.GPU) {
                Log.w(TAG, "GPU invoke failed, rebuilding CPU interpreter: ${t.message}")
                rebuildCpuLocked()
                return invoke(inputBuffer)
            }
            throw t
        }
    }

    private fun invoke(inputBuffer: ByteBuffer): FloatArray {
        inputBuffer.rewind()
        outputBuffer.rewind()
        interpreter.run(inputBuffer, outputBuffer)
        outputBuffer.rewind()
        outputBuffer.asFloatBuffer().get(outputArray)
        return outputArray
    }

    private fun rebuildCpuLocked() {
        try { interpreter.close() } catch (_: Exception) { }
        try { gpuDelegate?.close() } catch (_: Exception) { }
        gpuDelegate = null
        val state = createInterpreter(preferGpu = false)
        interpreter = state.interpreter
        gpuDelegate = state.delegate
        backend = state.backend
        Log.i(TAG, "Interpreter rebuilt backend=$backend")
    }

    private data class Loaded(
        val interpreter: Interpreter,
        val delegate: GpuDelegate?,
        val backend: Backend,
        val inputSize: Int,
        val outputLayout: OutputLayout,
        val numDetections: Int,
        val outputCols: Int,
    )

    private fun createInterpreter(preferGpu: Boolean): Loaded {
        if (preferGpu) {
            val gpu = tryCreateGpu()
            if (gpu != null) {
                try {
                    val loaded = finishLoad(gpu.interpreter, gpu.delegate, Backend.GPU)
                    warmup(loaded.interpreter, loaded.inputSize)
                    return loaded
                } catch (t: Throwable) {
                    Log.w(TAG, "GPU interpreter/warmup failed, falling back to CPU: ${t.message}")
                    try { gpu.interpreter.close() } catch (_: Exception) { }
                    try { gpu.delegate.close() } catch (_: Exception) { }
                }
            }
        }
        model.rewind()
        val cpu = Interpreter(model, Interpreter.Options().apply { setNumThreads(numThreads) })
        return try {
            val loaded = finishLoad(cpu, null, Backend.CPU)
            warmup(loaded.interpreter, loaded.inputSize)
            loaded
        } catch (t: Throwable) {
            try { cpu.close() } catch (_: Exception) { }
            throw t
        }
    }

    private data class GpuAttempt(val interpreter: Interpreter, val delegate: GpuDelegate)

    private fun tryCreateGpu(): GpuAttempt? {
        return try {
            val compat = CompatibilityList()
            if (!compat.isDelegateSupportedOnThisDevice) {
                Log.i(TAG, "GPU delegate not supported on this device")
                return null
            }
            val delegate = GpuDelegate(compat.bestOptionsForThisDevice)
            try {
                val opts = Interpreter.Options().apply { addDelegate(delegate) }
                model.rewind()
                GpuAttempt(Interpreter(model, opts), delegate)
            } catch (t: Throwable) {
                try { delegate.close() } catch (_: Exception) { }
                throw t
            }
        } catch (t: Throwable) {
            Log.w(TAG, "GPU delegate create failed: ${t.message}")
            null
        }
    }

    private fun finishLoad(
        interp: Interpreter,
        delegate: GpuDelegate?,
        backend: Backend,
    ): Loaded {
        val inputShape = interp.getInputTensor(0).shape()
        require(inputShape.size == 4 && inputShape[0] == 1 &&
            inputShape[1] == inputShape[2] && inputShape[3] == 3) {
            "Unexpected input tensor shape: ${inputShape.toList()}, expected [1,S,S,3]"
        }
        val outputShape = interp.getOutputTensor(0).shape()
        require(outputShape.size == 3 && outputShape[0] == 1) {
            "Unexpected output tensor shape: ${outputShape.toList()}"
        }
        val layout: OutputLayout
        val detections: Int
        val cols: Int
        when {
            outputShape[2] == 6 -> {
                layout = OutputLayout.NMS_XYXY
                detections = outputShape[1]
                cols = 6
            }
            outputShape[1] == 5 -> {
                layout = OutputLayout.RAW_CHW
                detections = outputShape[2]
                cols = 5
            }
            outputShape[2] == 5 -> {
                layout = OutputLayout.RAW_HWC
                detections = outputShape[1]
                cols = 5
            }
            else -> error("Unexpected output tensor shape: ${outputShape.toList()}")
        }
        return Loaded(interp, delegate, backend, inputShape[1], layout, detections, cols)
    }

    private fun warmup(interp: Interpreter, size: Int) {
        val bytes = size * size * 3 * 4
        val input = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
        val outShape = interp.getOutputTensor(0).shape()
        var elements = 1
        for (d in outShape) elements *= d
        val output = ByteBuffer.allocateDirect(elements * 4).order(ByteOrder.nativeOrder())
        interp.run(input, output)
    }

    private fun loadModelFile(context: Context, modelPath: String): MappedByteBuffer {
        return context.assets.openFd(modelPath).use { fd ->
            FileInputStream(fd.fileDescriptor).channel.map(
                FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength
            )
        }.apply { order(ByteOrder.nativeOrder()) }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val done = CountDownLatch(1)
        handler.post {
            try { interpreter.close() } catch (_: Exception) { }
            try { gpuDelegate?.close() } catch (_: Exception) { }
            gpuDelegate = null
            done.countDown()
            thread.quitSafely()
        }
        if (!done.await(2, TimeUnit.SECONDS)) {
            thread.quitSafely()
        }
    }

    companion object {
        private const val TAG = "YOLODetector"
    }
}
