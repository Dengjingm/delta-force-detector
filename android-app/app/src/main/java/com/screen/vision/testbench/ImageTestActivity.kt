package com.screen.vision.testbench

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.screen.vision.center.AimConfigStore
import com.screen.vision.center.ScreenCenterController
import com.screen.vision.center.VirtualReticleInjector
import com.screen.vision.detection.PostProcessor
import com.screen.vision.detection.Preprocessor
import com.screen.vision.detection.YOLODetector
import com.screen.vision.model.DetectResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * 应用内图片测试：选一张图，用同一套 YOLO 检测，再把准星从画面中心移到锁定目标。
 * 只画在本页，不注入系统触控，不需要 Root。
 */
class ImageTestActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var preview: ImageTestView
    private lateinit var statusText: TextView
    private lateinit var playButton: Button

    private var bitmap: Bitmap? = null
    private var detections: List<DetectResult> = emptyList()
    private var detector: YOLODetector? = null
    private var preprocessor: Preprocessor? = null
    private var postProcessor: PostProcessor? = null
    private var playJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildContent())
    }

    private fun buildContent(): ViewGroup {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
            setBackgroundColor(Color.rgb(18, 18, 18))
        }

        root.addView(TextView(this).apply {
            text = "图片测试"
            textSize = 22f
            setTextColor(Color.WHITE)
        })

        statusText = TextView(this).apply {
            text = "从相册选一张图。检测后画面会把锁定目标送到中心十字下，用来检查移动逻辑。\n只在本页演示，不会推动系统触控。"
            textSize = 13f
            setTextColor(Color.LTGRAY)
            setPadding(0, 16, 0, 16)
        }
        root.addView(statusText)

        preview = ImageTestView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        }
        root.addView(preview)

        root.addView(Button(this).apply {
            text = "选择图片"
            setOnClickListener { pickImage() }
        })

        playButton = Button(this).apply {
            text = "播放移动"
            isEnabled = false
            setOnClickListener { playMovement() }
        }
        root.addView(playButton)

        return root
    }

    private fun pickImage() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(Intent.createChooser(intent, "选择图片"), REQ_PICK_IMAGE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK_IMAGE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        playJob?.cancel()
        playButton.isEnabled = false
        statusText.text = "正在加载图片…"
        scope.launch {
            val decoded = withContext(Dispatchers.IO) { decodeImage(uri) }
            if (decoded == null) {
                statusText.text = "无法读取这张图片。"
                return@launch
            }
            bitmap?.recycle()
            bitmap = decoded
            detections = emptyList()
            preview.setImage(decoded)
            statusText.text = "图片 ${decoded.width}×${decoded.height}，正在检测…"
            runDetection(decoded)
        }
    }

    private fun decodeImage(uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > MAX_DECODE_SIDE || bounds.outHeight / sample > MAX_DECODE_SIDE) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return null
        return if (decoded.config == Bitmap.Config.ARGB_8888) decoded
        else decoded.copy(Bitmap.Config.ARGB_8888, false).also { decoded.recycle() }
    }

    private suspend fun runDetection(image: Bitmap) {
        try {
            detections = withContext(Dispatchers.Default) {
                val yolo = detector ?: YOLODetector(this@ImageTestActivity).also { detector = it }
                val pre = preprocessor ?: Preprocessor(yolo.inputSize).also { preprocessor = it }
                val post = postProcessor ?: PostProcessor(
                    classNames = listOf("enemy"),
                    outputLayout = yolo.outputLayout,
                ).also { postProcessor = it }
                val prepared = pre.preprocess(image)
                val raw = yolo.detect(prepared.inputBuffer)
                post.process(raw, prepared)
            }
        } catch (e: Exception) {
            statusText.text = "检测失败：${e.message}"
            playButton.isEnabled = false
            return
        }
        preview.setDetections(detections)
        if (detections.isEmpty()) {
            statusText.text = "没有检出目标。换一张有人物的图再试。"
            playButton.isEnabled = false
            return
        }
        playButton.isEnabled = true
        statusText.text = "检出 ${detections.size} 个目标，开始把目标送到中心。"
        playMovement()
    }

    private fun playMovement() {
        val image = bitmap ?: return
        if (detections.isEmpty()) return
        playJob?.cancel()
        playJob = scope.launch {
            val injector = VirtualReticleInjector(image.width / 2, image.height / 2)
            val cfg = AimConfigStore.get(this@ImageTestActivity).get().copy(enabled = true)
            val controller = ScreenCenterController(injector, cfg)
            val cx = image.width / 2
            val cy = image.height / 2
            preview.setPan(0, 0)
            preview.setReticleVisible(true)
            var settled = 0
            var frames = 0
            while (isActive && frames < MAX_PLAY_FRAMES) {
                val panX = injector.x - cx
                val panY = injector.y - cy
                val shifted = detections.map { shift(it, panX, panY) }
                controller.onFrame(shifted, image.width, image.height)
                val nextPanX = injector.x - cx
                val nextPanY = injector.y - cy
                preview.setPan(nextPanX, nextPanY)
                preview.setDetections(detections)
                frames++
                val nearest = shifted.minByOrNull { d ->
                    val dx = (d.x - cx).toLong()
                    val dy = (d.y - cy).toLong()
                    dx * dx + dy * dy
                }
                val errX = nearest?.let { abs(it.x - cx) } ?: 0
                val errY = nearest?.let { abs(it.y - cy) } ?: 0
                statusText.text = "第 $frames 步  镜头平移 ($nextPanX, $nextPanY)" +
                    nearest?.let { "  目标距中心 (${it.x - cx}, ${it.y - cy}) ${"%.2f".format(it.confidence)}" }.orEmpty()
                if (errX <= cfg.deadzonePx && errY <= cfg.deadzonePx) {
                    settled++
                    if (settled >= 3) break
                } else {
                    settled = 0
                }
                delay(FRAME_DELAY_MS)
            }
            controller.stop()
            statusText.text = statusText.text.toString() + "\n播放结束。可再点「播放移动」或另选一张图。"
        }
    }

    private fun shift(d: DetectResult, panX: Int, panY: Int): DetectResult =
        d.copy(
            x = d.x - panX,
            y = d.y - panY,
            x1 = d.x1 - panX,
            y1 = d.y1 - panY,
            x2 = d.x2 - panX,
            y2 = d.y2 - panY,
        )

    override fun onDestroy() {
        playJob?.cancel()
        scope.cancel()
        detector?.close()
        detector = null
        bitmap?.recycle()
        bitmap = null
        super.onDestroy()
    }

    companion object {
        private const val REQ_PICK_IMAGE = 2001
        private const val MAX_DECODE_SIDE = 1920
        private const val MAX_PLAY_FRAMES = 120
        private const val FRAME_DELAY_MS = 16L
    }
}
