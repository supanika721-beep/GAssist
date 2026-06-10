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
        if (goal.isEmpty()) { log("⚠ Enter a goal first"); return }
        if (!settingsManager.hasApiKey()) { log("⚠ API Key not set! Tap ⚙ Settings."); return }
        if (AutomationAccessibilityService.instance == null) {
            log("⚠ Accessibility Service not enabled!\nGo to Settings > Accessibility > GAssist")
            return
        }

        // STEP 1: Start foreground service DULU (wajib Android 14+ sebelum getMediaProjection)
        log("⏳ Starting foreground service...")
        val primeIntent = Intent(this, ScreenCaptureService::class.java)
        startForegroundService(primeIntent)

        // STEP 2: Baru request screen capture permission
        mainHandler.postDelayed({
            val projManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(projManager.createScreenCaptureIntent(), PROJECTION_REQUEST)
        }, 500) // jeda 500ms biar service sempat start
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PROJECTION_REQUEST && resultCode == Activity.RESULT_OK && data != null) {
            // STEP 3: Kirim projection data ke service yang sudah running
            val projIntent = Intent(this, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_START_PROJECTION
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
            }
            startForegroundService(projIntent)
            log("✅ Permission granted, capturing...")

            // STEP 4: Minimize app dulu biar screenshot layar luar (bukan UI GAssist)
            log("📱 Minimizing app in 2s...")
            mainHandler.postDelayed({
                moveTaskToBack(true) // minimize ke background
                mainHandler.postDelayed({ runAutomationLoop() }, 1000) // tunggu animasi minimize
            }, 2000)
        } else {
            log("✗ Screen capture permission denied")
        }
    }

    private fun runAutomationLoop() {
        val goal = etGoal.text.toString().trim()
        val apiKey = settingsManager.getApiKey()
        isRunning = true
        actionHistory.clear()

        mainHandler.post {
            btnRun.visibility = Button.GONE
            btnStop.visibility = Button.VISIBLE
            setStatus("Running...")
        }

        executor.execute {
            var stepCount = 0
            val maxSteps = 20

            while (isRunning && stepCount < maxSteps) {
                stepCount++
                log("[Step $stepCount]")

                val screenshot = ScreenCaptureService.instance?.captureScreen()
                if (screenshot == null) {
                    log("✗ Screenshot failed (service=${ScreenCaptureService.instance != null}, ready=${ScreenCaptureService.isReady})")
                    break
                }
                log("📷 Captured ${screenshot.width}x${screenshot.height}")

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
                    "tap"   -> accessibility?.performTap(action.x, action.y)
                    "swipe" -> accessibility?.performSwipe(action.x, action.y, action.x2, action.y2)
                    "type"  -> accessibility?.performType(action.text)
                    "wait"  -> Thread.sleep(1500)
                    "done"  -> { log("✅ Task complete!"); mainHandler.post { stopAutomation() }; return@execute }
                }

                Thread.sleep(1500)
            }

            if (stepCount >= maxSteps) log("⚠ Max steps reached")
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

    private fun formatParams(a: GeminiHelper.AiAction) = when (a.action) {
        "tap"   -> "(${a.x},${a.y})"
        "swipe" -> "(${a.x},${a.y})→(${a.x2},${a.y2})"
        "type"  -> "\"${a.text}\""
        else    -> ""
    }

    private fun log(msg: String) = mainHandler.post {
        tvLog.append("$msg\n")
        scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun setStatus(s: String) = mainHandler.post { tvStatus.text = "Status: $s" }
}
