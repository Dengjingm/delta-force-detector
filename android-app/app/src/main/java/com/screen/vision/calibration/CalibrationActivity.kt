package com.screen.vision.calibration

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.screen.vision.api.ResultBus
import com.screen.vision.center.AimConfigStore
import com.screen.vision.center.ScreenCenterConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * 程序化校准页 (无 XML, 沿用 MainActivity 风格): 调参并实时可视化瞄准几何。
 *
 * 参数经 [AimConfigStore.update] 持久化并热更新运行中的 [ScreenCenterController]。
 * 覆盖层订阅 [ResultBus] 显示实时检测读数。
 */
class CalibrationActivity : Activity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var store: AimConfigStore
    private lateinit var overlay: CalibrationView
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = AimConfigStore.get(this)
        val config = store.get()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
            setBackgroundColor(Color.rgb(18, 18, 18))
        }

        overlay = CalibrationView(this).apply {
            setSourceSize(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (240 * resources.displayMetrics.density).toInt(),
            )
        }
        root.addView(overlay)

        statusText = TextView(this).apply {
            text = "No detections"
            textSize = 13f
            setTextColor(Color.LTGRAY)
            setPadding(0, 16, 0, 16)
        }
        root.addView(statusText)

        addSeekRow(root, "sensitivity", 1, 30, (config.sensitivity * 10).toInt().coerceIn(1, 30),
            { v -> "%.1f".format(v / 10f) },
            { cfg, v -> cfg.copy(sensitivity = v / 10f) })
        addSeekRow(root, "deadzone px", 0, 50, config.deadzonePx,
            { v -> "$v" },
            { cfg, v -> cfg.copy(deadzonePx = v) })
        addSeekRow(root, "max step px", 10, 200, config.maxStepPx,
            { v -> "$v" },
            { cfg, v -> cfg.copy(maxStepPx = v) })
        addSeekRow(root, "associate distance px", 10, 200, config.associateDistancePx,
            { v -> "$v" },
            { cfg, v -> cfg.copy(associateDistancePx = v) })
        addSeekRow(root, "release after misses", 1, 20, config.releaseAfterMisses,
            { v -> "$v" },
            { cfg, v -> cfg.copy(releaseAfterMisses = v) })

        addSwitchRow(root, "enabled", config.enabled) { cfg, v -> cfg.copy(enabled = v) }
        addSwitchRow(root, "invert X", config.invertX) { cfg, v -> cfg.copy(invertX = v) }
        addSwitchRow(root, "invert Y", config.invertY) { cfg, v -> cfg.copy(invertY = v) }

        setContentView(root)

        scope.launch {
            ResultBus.results.collect { detections ->
                overlay.setDetections(detections)
                statusText.text = if (detections.isEmpty()) "No detections"
                    else "${detections.size} detection(s)"
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun rebuild(transform: (ScreenCenterConfig) -> ScreenCenterConfig) {
        store.update(transform(store.get()))
    }

    private fun addSeekRow(
        root: LinearLayout,
        label: String,
        minValue: Int,
        maxValue: Int,
        initial: Int,
        valueLabel: (Int) -> String,
        onChange: (ScreenCenterConfig, Int) -> ScreenCenterConfig,
    ) {
        root.addView(TextView(this).apply {
            text = label
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding(0, 12, 0, 0)
        })

        val valueText = TextView(this).apply {
            text = valueLabel(initial)
            textSize = 12f
            setTextColor(Color.LTGRAY)
        }
        root.addView(valueText)

        root.addView(SeekBar(this).apply {
            max = maxValue - minValue
            progress = initial - minValue
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                    val v = minValue + progress
                    valueText.text = valueLabel(v)
                    if (fromUser) rebuild { onChange(it, v) }
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        })
    }

    private fun addSwitchRow(
        root: LinearLayout,
        label: String,
        initial: Boolean,
        onChange: (ScreenCenterConfig, Boolean) -> ScreenCenterConfig,
    ) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        row.addView(TextView(this).apply {
            text = label
            textSize = 13f
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })

        row.addView(Switch(this).apply {
            isChecked = initial
            setOnCheckedChangeListener { _, checked -> rebuild { onChange(it, checked) } }
        })

        root.addView(row)
    }
}
