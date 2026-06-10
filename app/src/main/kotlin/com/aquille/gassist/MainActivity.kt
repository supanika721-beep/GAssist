package com.aquille.gassist

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var tvLog: TextView
    private lateinit var tvStatus: TextView
    private lateinit var etGoal: EditText
    private lateinit var btnRun: Button
    private lateinit var btnStop: Button
    private lateinit var btnSettings: Button
    private lateinit var scrollView: ScrollView
    private lateinit var settingsManager: SettingsManager

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isRunning = false
    private var actionHistory = StringBuilder()

    private val PROJECTION_REQUEST = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        settingsManager = SettingsManager(this)

        tvLog = findViewById(R.id.tvLog)
        tvStatus = findViewById(R.id.tvStatus)
        etGoal = findViewById(R.id.etGoal)
        btnRun = findViewById(R.id.btnRun)
        btnStop = findViewById(R.id.btnStop)
        btnSettings = findViewById(R.id.btnSettings)
        scrollView = findViewById(R.id.scrollView)

        btnRun.setOnClickListener { startAutomation() }
        btnStop.setOnClickListener { stopAutomation() }
        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun startAutomation() {
        val goal = etGoal.text.toString().trim()
        if (goal.isEmpty()) {
            log("⚠ Enter a goal first")
            return
        }

        if (!settingsManager.hasApiKey()) {
            log("⚠ API Key not set! Tap ⚙ Settings to add your Gemini API Key.")
            return
        }

        if (AutomationAccessibilityService.instance == null) {
            log("⚠ Accessibility Service not enabled!\nGo to Settings > Accessibility > GAssist")
            return
        }

        val projManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(projManager.createScreenCaptureIntent(), PROJECTION_REQUEST)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PROJECTION_REQUEST && resultCode == Activity.RESULT_OK && data != null) {
            val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
            }
            startForegroundService(serviceIntent)
            log("⏳ Starting screen capture service...")
            mainHandler.postDelayed({ runAutomationLoop() }, 2500)
        }
    }

    private fun runAutomationLoop() {
        val goal = etGoal.text.toString().trim()
        val apiKey = settingsManager.getApiKey()
        isRunning = true
        actionHistory.clear()

        btnRun.visibility = Button.GONE
        btnStop.visibility = Button.VISIBLE
        setStatus("Running...")

        executor.execute {
            var stepCount = 0
            val maxSteps = 20

            while (isRunning && stepCount < maxSteps) {
                stepCount++
                log("\n[Step $stepCount]")

                val screenshot = ScreenCaptureService.instance?.captureScreen()
                if (screenshot == null) {
                    val svcAlive = ScreenCaptureService.instance != null
                    log("✗ Screenshot failed (service alive: $svcAlive)")
                    break
                }
                log("📷 Screen captured (${screenshot.width}x${screenshot.height})")

                setStatus("Thinking...")
                val action = try {
                    GeminiHelper.analyze(
                        apiKey = apiKey,
                        screenshot = screenshot,
                        goal = goal,
                        history = actionHistory.toString(),
                        originalWidth = screenshot.width,
                        originalHeight = screenshot.height
                    )
                } catch (e: Exception) {
                    log("✗ API error: ${e.message}")
                    break
                }

                log("💭 ${action.thought}")
                log("▶ ${action.action} ${formatParams(action)}")
                actionHistory.append("${action.action}(${formatParams(action)}), ")

                val accessibility = AutomationAccessibilityService.instance
                when (action.action) {
                    "tap" -> accessibility?.performTap(action.x, action.y)
                    "swipe" -> accessibility?.performSwipe(action.x, action.y, action.x2, action.y2)
                    "type" -> accessibility?.performType(action.text)
                    "wait" -> Thread.sleep(1000)
                    "done" -> {
                        log("\n✅ Task complete!")
                        break
                    }
                }

                Thread.sleep(1500)
            }

            if (stepCount >= maxSteps) log("\n⚠ Max steps reached")
            mainHandler.post { stopAutomation() }
        }
    }

    private fun stopAutomation() {
        isRunning = false
        btnRun.visibility = Button.VISIBLE
        btnStop.visibility = Button.GONE
        setStatus("Idle")
        stopService(Intent(this, ScreenCaptureService::class.java))
    }

    private fun formatParams(action: GeminiHelper.AiAction): String {
        return when (action.action) {
            "tap" -> "(${action.x},${action.y})"
            "swipe" -> "(${action.x},${action.y})→(${action.x2},${action.y2})"
            "type" -> "\"${action.text}\""
            else -> ""
        }
    }

    private fun log(msg: String) {
        mainHandler.post {
            tvLog.append("$msg\n")
            scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun setStatus(status: String) {
        mainHandler.post { tvStatus.text = "Status: $status" }
    }
}
