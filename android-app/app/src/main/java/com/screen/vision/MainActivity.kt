package com.screen.vision

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.screen.vision.api.ScreenVisionSDK
import com.screen.vision.calibration.CalibrationActivity
import com.screen.vision.motion.MotionCalibration
import com.screen.vision.motion.MotionDiagnosticsSession
import com.screen.vision.motion.MotionDiagnosticsState
import com.screen.vision.simulation.InAppMotionEventDispatcher
import com.screen.vision.simulation.InAppPlaybackHandle
import com.screen.vision.simulation.InteractiveSimulationEngine
import com.screen.vision.simulation.Point2
import com.screen.vision.simulation.SimulationPadView
import com.screen.vision.simulation.SimulationRequest
import kotlin.math.PI

class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var motionText: TextView
    private lateinit var simulationText: TextView
    private lateinit var simulationPad: SimulationPadView
    private var motionDiagnostics: MotionDiagnosticsSession? = null
    private val simulationEngine = InteractiveSimulationEngine()
    private val motionEventDispatcher = InAppMotionEventDispatcher()
    private var simulationPlayback: InAppPlaybackHandle? = null

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
            text = "yolo-research"
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
            setOnClickListener { onStartClicked(moveCenter = false) }
        }
        root.addView(startBtn)

        val moveCenterBtn = Button(this).apply {
            text = "Start move center"
            setOnClickListener { onStartClicked(moveCenter = true) }
        }
        root.addView(moveCenterBtn)

        val stopBtn = Button(this).apply {
            text = "Stop"
            setOnClickListener { ScreenVisionSDK.stop(this@MainActivity) }
        }
        root.addView(stopBtn)

        val calibrationBtn = Button(this).apply {
            text = "Calibration"
            setOnClickListener {
                startActivity(Intent(this@MainActivity, CalibrationActivity::class.java))
            }
        }
        root.addView(calibrationBtn)

        motionText = TextView(this).apply {
            text = "Motion diagnostics stopped"
            textSize = 14f
            setTextColor(Color.LTGRAY)
            setPadding(0, 32, 0, 16)
        }
        root.addView(motionText)

        val startMotionDiagnostics = Button(this).apply {
            text = "Start motion diagnostics"
            setOnClickListener { startMotionDiagnostics() }
        }
        root.addView(startMotionDiagnostics)

        val stopMotionDiagnostics = Button(this).apply {
            text = "Stop motion diagnostics"
            setOnClickListener { motionDiagnostics?.stop() }
        }
        root.addView(stopMotionDiagnostics)

        simulationText = TextView(this).apply {
            text = "In-app simulation ready"
            textSize = 14f
            setTextColor(Color.LTGRAY)
            setPadding(0, 32, 0, 16)
        }
        root.addView(simulationText)

        simulationPad = SimulationPadView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (180 * resources.displayMetrics.density).toInt(),
            )
        }
        root.addView(simulationPad)

        val runSimulation = Button(this).apply {
            text = "Run in-app dual-modal simulation"
            setOnClickListener { runSimulationDemo() }
        }
        root.addView(runSimulation)

        return root
    }

    /** Captures touch delivered to this Activity while diagnostics are explicitly running. */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        motionDiagnostics?.onTouchEvent(event)
        return super.dispatchTouchEvent(event)
    }

    override fun onDestroy() {
        simulationPlayback?.cancel()
        simulationPlayback = null
        motionDiagnostics?.stop()
        motionDiagnostics = null
        super.onDestroy()
    }

    private fun runSimulationDemo() {
        if (simulationPad.width <= 0 || simulationPad.height <= 0) return
        simulationPlayback?.cancel()
        simulationEngine.reset()
        val trace = simulationEngine.synthesize(
            SimulationRequest(
                yawDeltaRad = Math.toRadians(12.0),
                pitchDeltaRad = Math.toRadians(3.0),
                touchStartPx = Point2(simulationPad.width * 0.25, simulationPad.height * 0.5),
                horizontalPixelsPerRad = simulationPad.width / PI,
                verticalPixelsPerRad = simulationPad.height / PI,
                requestedDurationNs = 450_000_000L,
                seed = 20260914L,
            ),
        )
        simulationText.text = "Playing: touch=${trace.touchSamples.size}, IMU=${trace.imuSamples.size}, " +
            "physicalGyroWeight=${"%.2f".format(trace.plan.retainedPhysicalGyroWeight)}"
        simulationPlayback = motionEventDispatcher.play(simulationPad, trace.touchSamples) { accepted ->
            simulationText.text = "Playback complete: accepted=$accepted, " +
                "touch=${trace.touchSamples.size}, IMU=${trace.imuSamples.size}"
            simulationPlayback = null
        }
    }

    private fun startMotionDiagnostics() {
        if (motionDiagnostics == null) {
            val metrics = resources.displayMetrics
            // Diagnostic starting profile: a full display width maps to PI radians. Replace with
            // a measured per-device/application calibration before interpreting risk scores.
            motionDiagnostics = MotionDiagnosticsSession(
                context = this,
                calibration = MotionCalibration(
                    touchYawRadPerPixel = (PI / metrics.widthPixels).toFloat(),
                    touchPitchRadPerPixel = (PI / metrics.heightPixels).toFloat(),
                ),
                stateListener = ::renderMotionState,
            )
        }
        val started = motionDiagnostics!!.start()
        if (!started) motionText.text = "Motion diagnostics unavailable: no gyroscope"
    }

    private fun renderMotionState(state: MotionDiagnosticsState) {
        val report = state.report
        motionText.text = buildString {
            append(if (state.running) "Motion diagnostics running" else "Motion diagnostics stopped")
            append("\nGyroscope: ")
            append(if (state.gyroscopeAvailable) "available" else "missing")
            append("\nSamples touch/gyro/view: ")
            append("${state.stats.touchSamples}/${state.stats.gyroSamples}/${state.stats.viewSamples}")
            if (report == null) {
                append("\nWaiting for independent view observations")
            } else {
                append("\nStatus: ${report.status}, score=${report.riskScore}")
                append("\nFindings: ${report.findings.joinToString { it.code.name }}")
            }
            append("\nCalibration: diagnostic placeholder; calibrate before evaluation")
        }
    }

    private fun onStartClicked(moveCenter: Boolean) {
        if (!hasModel()) {
            statusText.text = "Missing model: place model.tflite in assets/ and rebuild"
            return
        }
        ScreenVisionSDK.start(this, listOf("enemy"), moveCenter = moveCenter)
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
