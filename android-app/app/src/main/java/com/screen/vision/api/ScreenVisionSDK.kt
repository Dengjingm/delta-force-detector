package com.screen.vision.api

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.screen.vision.R
import com.screen.vision.model.DetectResult
import com.screen.vision.service.DetectionService
import com.screen.vision.update.ModelUpdater
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File

/**
 * ScreenVisionSDK — 屏幕元素实时定位 API。
 *
 * 使用方式:
 * ```kotlin
 * ScreenVisionSDK.start(context, listOf("enemy"), moveCenter = true)
 * ScreenVisionSDK.observe().collect { results -> ... }
 * ScreenVisionSDK.tap(x, y)
 * ScreenVisionSDK.stop(context)
 * ```
 *
 * YOLO 研究当前唯一类别为 `enemy`。
 */
object ScreenVisionSDK {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var isStarted = false
    @Volatile
    private var startGeneration = 0
    private var serviceIntent: Intent? = null
    private var _classNames: List<String> = emptyList()

    /**
     * 启动检测引擎。前台服务独立于界面，切到其他应用后仍采集整屏并注入。
     *
     * @param context   Application context
     * @param classNames 类别名称列表，顺序必须与训练时的 dataset.yaml 一致
     * @param modelPath  TFLite 模型路径 (assets/ 或已下载的路径)
     * @param moveCenter 是否把屏幕中心朝最近检测目标移动
     */
    fun start(context: Context, classNames: List<String>, modelPath: String = "model.tflite", moveCenter: Boolean = false) {
        if (isStarted) return
        isStarted = true
        _classNames = classNames
        val app = context.applicationContext
        val generation = ++startGeneration

        scope.launch(Dispatchers.IO) { launchDaemon(app) }

        scope.launch {
            val updater = ModelUpdater(app)
            updater.checkAndUpdate(onResult = { _, _ -> })
        }

        serviceIntent = Intent(app, DetectionService::class.java).apply {
            putExtra(DetectionService.EXTRA_CLASS_NAMES, classNames.toTypedArray())
            putExtra(DetectionService.EXTRA_MODEL_PATH, modelPath)
            putExtra(DetectionService.EXTRA_MOVE_CENTER, moveCenter)
            putExtra(DetectionService.EXTRA_START_GENERATION, generation)
        }
        app.startForegroundService(serviceIntent!!)

        scope.launch {
            delay(500)
            bridgeResults(app)
        }

        Log.i(TAG, "Started with ${classNames.size} classes: $classNames moveCenter=$moveCenter")
    }

    /** 把 APK 内的 daemon 解到 /data/local/tmp 并后台拉起，不绑在 App 界面生命周期上。 */
    private fun launchDaemon(context: Context) {
        try {
            val staged = File(context.filesDir, "screen-visiond")
            context.resources.openRawResource(R.raw.screen_visiond).use { input ->
                staged.outputStream().use { output -> input.copyTo(output) }
            }
            staged.setReadable(true, false)
            staged.setExecutable(true, false)
            val stagedPath = staged.absolutePath
            val cmd = "cp '$stagedPath' /data/local/tmp/screen-visiond" +
                " && chmod 755 /data/local/tmp/screen-visiond" +
                " && (nohup /data/local/tmp/screen-visiond >/dev/null 2>&1 &)"
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val code = p.waitFor()
            if (code != 0) Log.e(TAG, "Daemon install/launch exited $code")
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

    fun observe(): SharedFlow<List<DetectResult>> = ResultBus.results

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
        startGeneration++
        val app = context.applicationContext
        try { app.unbindService(serviceConnection) } catch (_: Exception) { }
        serviceIntent?.let { app.stopService(it) }
        serviceIntent = null
        isStarted = false
        try { Runtime.getRuntime().exec(arrayOf("su", "-c", "killall screen-visiond")) } catch (_: Exception) { }
        Log.i(TAG, "Stopped")
    }

    /** Service 自行退出时清标志；若用户已经重新 start，则忽略旧实例。 */
    fun notifyServiceStopped(generation: Int) {
        if (generation != startGeneration) return
        isStarted = false
        serviceIntent = null
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
