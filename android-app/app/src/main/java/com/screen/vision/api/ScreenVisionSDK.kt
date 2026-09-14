package com.screen.vision.api

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.screen.vision.model.DetectResult
import com.screen.vision.service.DetectionService
import com.screen.vision.update.ModelUpdater
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * ScreenVisionSDK — 屏幕元素实时定位 API。
 *
 * 使用方式:
 * ```kotlin
 * ScreenVisionSDK.start(context, listOf("enemy"))
 * ScreenVisionSDK.observe().collect { results -> ... }
 * ScreenVisionSDK.tap(x, y)
 * ScreenVisionSDK.stop(context)
 * ```
 *
 * 三角洲行动 (Delta Force) 当前唯一类别为 `enemy`。
 */
object ScreenVisionSDK {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _results = MutableSharedFlow<List<DetectResult>>(replay = 1, extraBufferCapacity = 4)

    @Volatile
    private var isStarted = false
    private var serviceIntent: Intent? = null
    private var _classNames: List<String> = emptyList()

    /**
     * 启动检测引擎。
     *
     * @param context   Application context
     * @param classNames 类别名称列表，顺序必须与训练时的 dataset.yaml 一致
     * @param modelPath  TFLite 模型路径 (assets/ 或已下载的路径)
     */
    fun start(context: Context, classNames: List<String>, modelPath: String = "model.tflite") {
        if (isStarted) return
        isStarted = true
        _classNames = classNames

        launchDaemon()

        scope.launch {
            val updater = ModelUpdater(context)
            updater.checkAndUpdate(onResult = { _, _ -> })
        }

        serviceIntent = Intent(context, DetectionService::class.java).apply {
            putExtra(DetectionService.EXTRA_CLASS_NAMES, classNames.toTypedArray())
            putExtra(DetectionService.EXTRA_MODEL_PATH, modelPath)
        }
        context.startForegroundService(serviceIntent!!)

        scope.launch {
            delay(500)
            bridgeResults(context)
        }

        Log.i(TAG, "Started with ${classNames.size} classes: $classNames")
    }

    private fun launchDaemon() {
        try {
            Runtime.getRuntime().exec(arrayOf("su", "-c", "/data/local/tmp/screen-visiond"))
        } catch (e: Exception) {
            Log.e(TAG, "Daemon launch failed: ${e.message}")
        }
    }

    private suspend fun bridgeResults(context: Context) {
        try {
            context.bindService(
                Intent(context, DetectionService::class.java),
                serviceConnection, Context.BIND_AUTO_CREATE
            )
        } catch (e: Exception) {
            Log.e(TAG, "Bind failed: ${e.message}")
        }
    }

    fun setResultSource(flow: SharedFlow<List<DetectResult>>) {
        scope.launch { flow.collect { _results.emit(it) } }
    }

    fun observe(): SharedFlow<List<DetectResult>> = _results

    fun tap(x: Int, y: Int): Boolean {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "input tap $x $y"))
            p.waitFor() == 0
        } catch (e: Exception) {
            Log.e(TAG, "Tap failed: ${e.message}"); false
        }
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 100): Boolean {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "input swipe $x1 $y1 $x2 $y2 $durationMs"))
            p.waitFor() == 0
        } catch (e: Exception) {
            Log.e(TAG, "Swipe failed: ${e.message}"); false
        }
    }

    fun stop(context: Context) {
        if (!isStarted) return
        try { context.unbindService(serviceConnection) } catch (_: Exception) { }
        serviceIntent?.let { context.stopService(it) }
        serviceIntent = null
        isStarted = false
        try { Runtime.getRuntime().exec(arrayOf("su", "-c", "killall screen-visiond")) } catch (_: Exception) { }
        Log.i(TAG, "Stopped")
    }

    fun isRunning(): Boolean = isStarted

    val classNames: List<String> get() = _classNames

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.i(TAG, "DetectionService connected")
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "DetectionService disconnected")
        }
    }

    private const val TAG = "ScreenVisionSDK"
}