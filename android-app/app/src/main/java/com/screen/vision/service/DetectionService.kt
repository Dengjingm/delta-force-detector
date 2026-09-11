package com.screen.vision.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.screen.vision.detection.PostProcessor
import com.screen.vision.detection.Preprocessor
import com.screen.vision.detection.YOLODetector
import com.screen.vision.model.DetectResult
import com.screen.vision.socket.UnixSocketClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * 前台 Service — 30fps 检测主循环。
 *
 * 连接 Native Socket 接收帧 → 预处理 → TFLite 推理 → 后处理 → 发射结果
 */
class DetectionService : Service() {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var isRunning = false

    private lateinit var socketClient: UnixSocketClient
    private lateinit var preprocessor: Preprocessor
    private lateinit var detector: YOLODetector
    private lateinit var postProcessor: PostProcessor

    private val _results = MutableSharedFlow<List<DetectResult>>(replay = 1, extraBufferCapacity = 2)
    val results: SharedFlow<List<DetectResult>> = _results

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        initComponents()
    }

    private fun initComponents() {
        socketClient = UnixSocketClient()
        preprocessor = Preprocessor(inputSize = 960)
        // detector/postProcessor 由 SDK 传入 classNames 后初始化
    }

    /**
     * 由 ScreenVisionSDK 调用，注入训练好的类别名称
     */
    fun initWithClasses(classNames: List<String>, modelPath: String = "model.tflite") {
        detector = YOLODetector(this, modelPath, classNames = classNames)
        postProcessor = PostProcessor(classNames = classNames)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (isRunning) return START_STICKY
        isRunning = true

        val classNames = intent?.getStringArrayExtra(EXTRA_CLASS_NAMES)?.toList()
            ?: DEFAULT_CLASS_NAMES.toList()
        val modelPath = intent?.getStringExtra(EXTRA_MODEL_PATH) ?: "model.tflite"

        initWithClasses(classNames, modelPath)

        scope.launch { runDetectionLoop() }
        return START_STICKY
    }

    private suspend fun runDetectionLoop() {
        if (!socketClient.connect()) {
            Log.e(TAG, "Failed to connect to native daemon")
            _results.emit(emptyList())
            return
        }
        Log.i(TAG, "Connected, starting 15fps loop")

        var frameCount = 0
        var totalTimeUs = 0L
        val targetFrameTimeNs = 66_666_666L  # 15fps

        while (isActive) {
            val frameStart = System.nanoTime()

            val bitmap = socketClient.readFrame() ?: break
            val preprocessed = preprocessor.preprocess(bitmap)
            bitmap.recycle()

            val rawOutput = detector.detect(preprocessed.inputBuffer)
            val detections = postProcessor.process(rawOutput, preprocessed)

            if (detections.isNotEmpty()) _results.tryEmit(detections)

            frameCount++
            totalTimeUs += (System.nanoTime() - frameStart) / 1000
            if (frameCount % 300 == 0) {
                Log.i(TAG, "[STATS] ${frameCount}f | avg: ${totalTimeUs / frameCount}us")
            }

            val elapsed = System.nanoTime() - frameStart
            if (elapsed < targetFrameTimeNs) {
                val sleepNs = targetFrameTimeNs - elapsed
                Thread.sleep(sleepNs / 1_000_000, (sleepNs % 1_000_000).toInt())
            }
        }
        cleanup()
    }

    private fun cleanup() {
        isRunning = false
        try { socketClient.disconnect() } catch (_: Exception) { }
        try { detector.close() } catch (_: Exception) { }
    }

    override fun onBind(intent: Intent?) = results

    override fun onDestroy() {
        isRunning = false; scope.cancel(); cleanup(); super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Screen Vision",
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Screen Vision")
            .setContentText("15fps detection running...")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    companion object {
        private const val TAG = "DetectionService"
        private const val CHANNEL_ID = "screen_vision"
        private const val NOTIFICATION_ID = 1001
        const val EXTRA_CLASS_NAMES = "class_names"
        const val EXTRA_MODEL_PATH = "model_path"

        val DEFAULT_CLASS_NAMES = arrayOf("enemy")
    }
}