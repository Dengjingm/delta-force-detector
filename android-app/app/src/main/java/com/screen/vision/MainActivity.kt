package com.screen.vision

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.screen.vision.api.ScreenVisionSDK
import com.screen.vision.calibration.CalibrationActivity
import com.screen.vision.testbench.ImageTestActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private val uiHandler = Handler(Looper.getMainLooper())
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observeJob: Job? = null
    private var lastDetectionLine = "等待首帧…"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildContent())
        observeDetections()
        ensureNotificationPermissionThenStart()
    }

    private fun buildContent(): ViewGroup {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 48)
            setBackgroundColor(Color.rgb(18, 18, 18))
        }

        root.addView(TextView(this).apply {
            text = "yolo-research"
            textSize = 22f
            setTextColor(Color.WHITE)
        })

        statusText = TextView(this).apply {
            text = "正在启动后台检测…"
            textSize = 14f
            setTextColor(Color.LTGRAY)
            setPadding(0, 24, 0, 24)
        }
        root.addView(statusText)

        root.addView(Button(this).apply {
            text = "停止后台检测"
            setOnClickListener {
                ScreenVisionSDK.stop(applicationContext)
                lastDetectionLine = "已停止"
                refreshStatus(running = false)
            }
        })

        root.addView(Button(this).apply {
            text = "重新启动"
            setOnClickListener {
                ScreenVisionSDK.stop(applicationContext)
                lastDetectionLine = "等待首帧…"
                refreshStatus(running = false)
                uiHandler.postDelayed({ startBackgroundPipeline() }, 400)
            }
        })

        root.addView(Button(this).apply {
            text = "校准"
            setOnClickListener {
                startActivity(Intent(this@MainActivity, CalibrationActivity::class.java))
            }
        })

        root.addView(Button(this).apply {
            text = "图片测试"
            setOnClickListener {
                startActivity(Intent(this@MainActivity, ImageTestActivity::class.java))
            }
        })

        val scroll = ScrollView(this)
        scroll.addView(root)
        return scroll
    }

    private fun observeDetections() {
        observeJob?.cancel()
        observeJob = uiScope.launch {
            ScreenVisionSDK.observe().collect { results ->
                lastDetectionLine = if (results.isEmpty()) {
                    "本帧无目标"
                } else {
                    val first = results.first()
                    "本帧 ${results.size} 个目标，最近 (${first.x}, ${first.y}) ${"%.2f".format(first.confidence)}"
                }
                refreshStatus(running = ScreenVisionSDK.isRunning())
            }
        }
    }

    private fun ensureNotificationPermissionThenStart() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_POST_NOTIFICATIONS)
            return
        }
        startBackgroundPipeline()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_POST_NOTIFICATIONS) startBackgroundPipeline()
    }

    private fun startBackgroundPipeline() {
        if (!hasModel()) {
            statusText.text = "安装包里没有模型。请把 model.tflite 放到 assets 后重新打包。"
            return
        }
        statusText.text = "正在检查 Root 并启动后台检测…"
        Thread {
            val rooted = hasRoot()
            uiHandler.post {
                if (!rooted) {
                    statusText.text = "需要 Root。授权 su 后点「重新启动」。\n" +
                        "检测采的是整块屏幕，不是本 App 画面；没有 Root 就采不到其他应用。"
                    return@post
                }
                ScreenVisionSDK.start(
                    applicationContext,
                    listOf("enemy"),
                    moveCenter = true,
                )
                refreshStatus(running = true)
            }
        }.start()
    }

    private fun refreshStatus(running: Boolean) {
        if (!::statusText.isInitialized) return
        statusText.text = if (running) {
            "后台检测已启动（含屏幕中心移动）\n" +
                "$lastDetectionLine\n\n" +
                "现在可以切到游戏或其他应用。采的是整块屏幕，不依赖本页是否在前台。\n" +
                "通知栏会一直显示；只有点「停止后台检测」才会关掉。\n" +
                "若切走后被系统杀掉，把本应用耗电设为无限制。"
        } else {
            "后台检测未运行。\n点「重新启动」后即可切到其他应用。"
        }
    }

    override fun onDestroy() {
        observeJob?.cancel()
        uiScope.cancel()
        super.onDestroy()
    }

    private fun hasModel(): Boolean {
        return try {
            assets.open("model.tflite").close()
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun hasRoot(): Boolean {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val code = p.waitFor()
            p.destroy()
            code == 0
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        private const val REQ_POST_NOTIFICATIONS = 1001
    }
}
