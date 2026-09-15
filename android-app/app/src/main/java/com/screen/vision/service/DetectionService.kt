package com.screen.vision.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.screen.vision.MainActivity
import com.screen.vision.api.ScreenVisionSDK
import com.screen.vision.center.AimConfigStore
import com.screen.vision.center.AimInjector
import com.screen.vision.center.DaemonAimInjector
import com.screen.vision.center.ScreenCenterController
import com.screen.vision.center.TouchInjector
import com.screen.vision.control.ControlClient
import com.screen.vision.api.ResultBus
import com.screen.vision.detection.PostProcessor
import com.screen.vision.detection.Preprocessor
import com.screen.vision.detection.YOLODetector
import com.screen.vision.model.DetectResult
import com.screen.vision.socket.ReceivedFrame
import com.screen.vision.socket.UnixSocketClient
import com.screen.vision.util.LatestSlot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class DetectionService : Service() {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var isRunning = false
    private var modelLoaded = false

    private lateinit var socketClient: UnixSocketClient
    private lateinit var preprocessor: Preprocessor
    private lateinit var detector: YOLODetector
    private lateinit var postProcessor: PostProcessor
    private var centerController: ScreenCenterController? = null
    private var aimConfigStore: AimConfigStore? = null
    private var moveCenterRequested = false
    private var startGeneration = 0
    private val frameSlot = LatestSlot<ReceivedFrame>()
    private val aimSlot = LatestSlot<AimFrame>()
    private val readerDone = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        socketClient = UnixSocketClient()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (isRunning) return START_NOT_STICKY
        isRunning = true

        val classNames = intent?.getStringArrayExtra(EXTRA_CLASS_NAMES)?.toList()
            ?: DEFAULT_CLASS_NAMES.toList()
        val modelPath = intent?.getStringExtra(EXTRA_MODEL_PATH) ?: "model.tflite"
        moveCenterRequested = intent?.getBooleanExtra(EXTRA_MOVE_CENTER, false) ?: false
        startGeneration = intent?.getIntExtra(EXTRA_START_GENERATION, 0) ?: 0

        if (!initModel(classNames, modelPath)) {
            Log.e(TAG, "Model init failed; aborting start")
            isRunning = false
            ResultBus.publish(emptyList())
            ScreenVisionSDK.notifyServiceStopped(startGeneration)
            stopSelf()
            return START_NOT_STICKY
        }

        scope.launch { runDetectionLoop() }
        return START_NOT_STICKY
    }

    private fun initModel(classNames: List<String>, modelPath: String): Boolean {
        return try {
            detector = YOLODetector(this, modelPath)
            preprocessor = Preprocessor(inputSize = detector.inputSize)
            postProcessor = PostProcessor(
                classNames = classNames,
                outputLayout = detector.outputLayout,
            )
            modelLoaded = true
            Log.i(TAG, "Model ready backend=${detector.backend} input=${detector.inputSize}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Model load failed ($modelPath): ${e.message}")
            false
        }
    }

    /** 控制 socket 可达时用 daemon 注入, 否则回退 su 注入。 */
    private fun chooseInjector(): AimInjector {
        val probe = ControlClient()
        return if (probe.connect()) {
            probe.close()
            DaemonAimInjector()
        } else {
            probe.close()
            TouchInjector()
        }
    }

    private suspend fun runDetectionLoop() {
        if (!connectWithRetry()) {
            Log.e(TAG, "Failed to connect to native daemon")
            cleanup()
            ScreenVisionSDK.notifyServiceStopped(startGeneration)
            stopSelf()
            return
        }
        attachCenterControllerIfNeeded()
        Log.i(TAG, "Connected, starting detection loop")

        readerDone.set(false)
        scope.launch(Dispatchers.IO) { readLoop() }
        if (moveCenterRequested) {
            scope.launch { runAimLoop() }
        }

        var frameCount = 0
        var totalTimeUs = 0L
        var totalFrameAgeUs = 0L

        while (scope.isActive) {
            val frameStart = System.nanoTime()

            val frame = frameSlot.take()
            if (frame == null) {
                if (readerDone.get()) break
                delay(2)
                continue
            }

            val frameAgeUs = (SystemClock.elapsedRealtimeNanos() - frame.header.captureEndNs) / 1000
            val preprocessed = preprocessor.preprocess(frame.bitmap)
            frame.bitmap.recycle()

            val rawOutput = detector.detect(preprocessed.inputBuffer)
            val detections = postProcessor.process(rawOutput, preprocessed)

            ResultBus.publish(detections)
            aimSlot.publish(
                AimFrame(detections, preprocessed.originalWidth, preprocessed.originalHeight),
            )

            frameCount++
            totalTimeUs += (System.nanoTime() - frameStart) / 1000
            totalFrameAgeUs += frameAgeUs
            if (frameCount % 300 == 0) {
                Log.i(TAG, "[STATS] ${frameCount}f | avg: ${totalTimeUs / frameCount}us | frameAge: ${totalFrameAgeUs / frameCount}us")
            }
        }

        cleanup()
        ScreenVisionSDK.notifyServiceStopped(startGeneration)
        stopSelf()
    }

    private suspend fun runAimLoop() {
        while (scope.isActive) {
            val frame = aimSlot.take()
            if (frame == null) {
                delay(1)
                continue
            }
            try {
                centerController?.onFrame(frame.detections, frame.width, frame.height)
            } catch (e: Exception) {
                Log.e(TAG, "aim frame failed: ${e.message}")
            }
        }
    }

    /** daemon 由 su 异步拉起，首连经常还没 listen，给一段短重试。 */
    private suspend fun connectWithRetry(): Boolean {
        repeat(CONNECT_ATTEMPTS) { attempt ->
            if (socketClient.connect()) return true
            Log.w(TAG, "Daemon connect attempt ${attempt + 1}/$CONNECT_ATTEMPTS failed")
            delay(CONNECT_RETRY_MS)
        }
        return false
    }

    private fun attachCenterControllerIfNeeded() {
        if (!moveCenterRequested || centerController != null) return
        aimConfigStore = AimConfigStore.get(this)
        val injector = chooseInjector()
        val store = aimConfigStore!!
        centerController = ScreenCenterController(injector, store.get())
        store.attach(centerController)
    }

    /** 阻塞读取 socket 并写入最新帧槽；被替换的旧帧立即回收。 */
    private fun readLoop() {
        try {
            while (scope.isActive) {
                val frame = socketClient.readFrame() ?: break
                frameSlot.publish(frame)?.bitmap?.recycle()
            }
        } finally {
            readerDone.set(true)
            // 读端结束后槽中残留帧不会再被消费，由读端回收，避免关闭竞态下的泄漏。
            frameSlot.clear()?.bitmap?.recycle()
        }
    }

    private fun cleanup() {
        centerController?.stop()
        centerController = null
        aimConfigStore?.attach(null)
        aimConfigStore = null
        ResultBus.publish(emptyList())
        isRunning = false
        aimSlot.clear()
        try { socketClient.disconnect() } catch (_: Exception) { }
        if (modelLoaded) {
            try { detector.close() } catch (_: Exception) { }
            modelLoaded = false
        }
    }

    override fun onBind(intent: Intent?): IBinder = Binder()

    override fun onDestroy() {
        isRunning = false
        ScreenVisionSDK.notifyServiceStopped(startGeneration)
        scope.cancel()
        cleanup()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "yolo-research",
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val launch = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("yolo-research")
            .setContentText("正在后台采集全屏并移动中心，可切到其他应用")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(launch)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val TAG = "DetectionService"
        private const val CHANNEL_ID = "screen_vision"
        private const val NOTIFICATION_ID = 1001
        const val EXTRA_CLASS_NAMES = "class_names"
        const val EXTRA_MODEL_PATH = "model_path"
        const val EXTRA_MOVE_CENTER = "move_center"
        const val EXTRA_START_GENERATION = "start_generation"
        private const val CONNECT_ATTEMPTS = 20
        private const val CONNECT_RETRY_MS = 500L

        val DEFAULT_CLASS_NAMES = arrayOf("enemy")
    }

    private data class AimFrame(
        val detections: List<DetectResult>,
        val width: Int,
        val height: Int,
    )
}
