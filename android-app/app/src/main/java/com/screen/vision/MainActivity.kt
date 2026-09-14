package com.screen.vision

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.screen.vision.api.ScreenVisionSDK

class MainActivity : Activity() {

    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildContent())
        refreshStatus()
    }

    private fun buildContent(): ViewGroup {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 48)
            setBackgroundColor(Color.rgb(18, 18, 18))
        }

        val title = TextView(this).apply {
            text = "Screen Vision Demo"
            textSize = 22f
            setTextColor(Color.WHITE)
        }
        root.addView(title)

        statusText = TextView(this).apply {
            text = "Checking..."
            textSize = 14f
            setTextColor(Color.LTGRAY)
            setPadding(0, 24, 0, 24)
        }
        root.addView(statusText)

        val startBtn = Button(this).apply {
            text = "Start detection"
            setOnClickListener { onStartClicked(autoAim = false) }
        }
        root.addView(startBtn)

        val aimBtn = Button(this).apply {
            text = "Start auto-aim"
            setOnClickListener { onStartClicked(autoAim = true) }
        }
        root.addView(aimBtn)

        val stopBtn = Button(this).apply {
            text = "Stop"
            setOnClickListener { ScreenVisionSDK.stop(this@MainActivity) }
        }
        root.addView(stopBtn)

        return root
    }

    private fun onStartClicked(autoAim: Boolean) {
        if (!hasModel()) {
            statusText.text = "Missing model: place model.tflite in assets/ and rebuild"
            return
        }
        ScreenVisionSDK.start(this, listOf("enemy"), autoAim = autoAim)
        refreshStatus()
    }

    private fun refreshStatus() {
        Thread {
            val model = if (hasModel()) "present" else "MISSING"
            val root = if (hasRoot()) "available" else "UNAVAILABLE"
            runOnUiThread {
                statusText.text = "Model: $model\nRoot: $root\n\n" +
                    "Root + model required for live detection."
            }
        }.start()
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
}
