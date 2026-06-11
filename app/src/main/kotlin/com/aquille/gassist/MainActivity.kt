package com.aquille.gassist

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    // ── UI ──────────────────────────────────────────────────────────────────
    private lateinit var tvLog:      TextView
    private lateinit var tvStatus:   TextView
    private lateinit var etGoal:     EditText
    private lateinit var btnRun:     Button
    private lateinit var btnStop:    Button
    private lateinit var btnSettings: Button
    private lateinit var scrollView: ScrollView

    // ── Helpers ─────────────────────────────────────────────────────────────
    private lateinit var settingsManager: SettingsManager
    private lateinit var overlayManager:  OverlayManager

    private val executor    = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var isRunning     = false
    private val actionHistory           = StringBuilder()
    private val PROJECTION_REQUEST      = 100
    private val OVERLAY_PERMISSION_REQ  = 101

    // ── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        settingsManager = SettingsManager(this)
        overlayManager  = OverlayManager(this).apply {
            onStopClicked = { stopAutomation() }
        }

        tvLog      = findViewById(R.id.tvLog)
        tvStatus   = findViewById(R.id.tvStatus)
        etGoal     = findViewById(R.id.etGoal)
        btnRun     = findViewById(R.id.btnRun)
        btnStop    = findViewById(R.id.btnStop)
        btnSettings= findViewById(R.id.btnSettings)
        scrollView = findViewById(R.id.scrollView)

        btnRun.setOnClickListener     { startAutomation() }
        btnStop.setOnClickListener    { stopAutomation() }
        btnSettings.setOnClickListener{ startActivity(Intent(this, SettingsActivity::class.java)) }

        // Log awal hanya ditampilkan setelah semua cek selesai
        runStartupChecks()
    }

    // ── Startup Checks ────────────────────────────────────────────────────────

    private fun runStartupChecks() {
        // 1. Cek internet
        if (!NetworkUtils.isInternetAvailable(this)) {
            showNoInternetDialog()
            return
        }

        // 2. Cek accessibility
        if (!isAccessibilityEnabled()) {
            showAccessibilityDialog()
            return
        }

        // 3. Semua OK
        log("[GAssist Ready]")
        setStatus("Idle")
    }

    private fun isAccessibilityEnabled(): Boolean {
        val service = "$packageName/${AutomationAccessibilityService::class.java.canonicalName}"
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.contains(service)
    }

    private fun showNoInternetDialog() {
        AlertDialog.Builder(this)
            .setTitle("No Internet Connection")
            .setMessage("GAssist requires an active internet connection to work. Please connect and try again.")
            .setCancelable(false)
            .setPositiveButton("OK") { _, _ -> finish() }
            .show()
    }

    private fun showAccessibilityDialog() {
        AlertDialog.Builder(this)
            .setTitle("Accessibility Permission Required")
            .setMessage(
                "GAssist needs Accessibility Service access to control your screen.\n\n" +
                "Tap OK to open Accessibility Settings, then enable GAssist."
            )
            .setCancelable(false)
            .setPositiveButton("OK") { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .show()
    }

    override fun onResume() {
        super.onResume()
        // Re-cek setelah user balik dari Accessibility/Overlay settings
        // Jangan re-run kalau automation sedang berjalan
        if (!isRunning) {
            val logText = tvLog.text.toString()
            when {
                // Belum ada teks apapun → jalankan startup check penuh
                logText.isEmpty() -> runStartupChecks()
                // Sudah ada log tapi accessibility baru diaktifkan → tampilkan Ready
                !logText.contains("[GAssist Ready]")
                    && isAccessibilityEnabled()
                    && NetworkUtils.isInternetAvailable(this) -> {
                    log("[GAssist Ready]")
                    setStatus("Idle")
                }
            }
        }
    }

    // ── Automation Flow ───────────────────────────────────────────────────────

    private fun startAutomation() {
        val goal = etGoal.text.toString().trim()
        if (goal.isEmpty())              { log("⚠ Enter a goal first"); return }
        if (!NetworkUtils.isInternetAvailable(this)) { showNoInternetDialog(); return }
        if (!settingsManager.hasApiKey()){ log("⚠ API Key not set! Tap ⚙ Settings."); return }
        if (!isAccessibilityEnabled())   { showAccessibilityDialog(); return }

        // Cek overlay permission (SYSTEM_ALERT_WINDOW) untuk "AI is working" overlay
        if (!Settings.canDrawOverlays(this)) {
            AlertDialog.Builder(this)
                .setTitle("Overlay Permission Needed")
                .setMessage("GAssist needs 'Appear on top' permission to show the AI overlay while working.")
                .setPositiveButton("Grant") { _, _ ->
                    startActivityForResult(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                               Uri.parse("package:$packageName")),
                        OVERLAY_PERMISSION_REQ
                    )
                }
                .setNegativeButton("Skip") { _, _ -> proceedToCapture() }
                .show()
            return
        }

        proceedToCapture()
    }

    private fun proceedToCapture() {
        log("⏳ Starting capture service...")
        // STEP 1: foreground service jalan DULU
        startForegroundService(Intent(this, ScreenCaptureService::class.java))

        // STEP 2: request permission (500ms jeda biar service beneran running)
        mainHandler.postDelayed({
            val pm = getSystemService(MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
            startActivityForResult(pm.createScreenCaptureIntent(), PROJECTION_REQUEST)
        }, 500)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            OVERLAY_PERMISSION_REQ -> {
                // Dari halaman overlay permission — langsung lanjut
                proceedToCapture()
            }
            PROJECTION_REQUEST -> {
                if (resultCode == Activity.RESULT_OK && data != null) {
                    // STEP 3: Kirim projection data ke service yang sudah running
                    val initIntent = Intent(this, ScreenCaptureService::class.java).apply {
                        action = ScreenCaptureService.ACTION_INIT
                        putExtra(ScreenCaptureService.EXTRA_CODE, resultCode)
                        putExtra(ScreenCaptureService.EXTRA_DATA, data)
                    }
                    startForegroundService(initIntent)
                    log("✅ Permission granted")

                    // STEP 4: Minimize app → screenshot layar luar
                    log("📱 Minimizing in 2s...")
                    mainHandler.postDelayed({
                        moveTaskToBack(true)
                        mainHandler.postDelayed({ runLoop() }, 1000)
                    }, 2000)
                } else {
                    log("✗ Screen capture denied")
                }
            }
        }
    }

    // ── Main Loop ─────────────────────────────────────────────────────────────

    private fun runLoop() {
        val goal   = etGoal.text.toString().trim()
        val apiKey = settingsManager.getApiKey()
        isRunning  = true
        actionHistory.clear()

        // Tampilkan overlay "AI is working"
        if (Settings.canDrawOverlays(this)) overlayManager.show()

        mainHandler.post {
            btnRun.visibility  = Button.GONE
            btnStop.visibility = Button.VISIBLE
            setStatus("Running...")
        }

        executor.execute {
            var step = 0
            val maxSteps = 20

            while (isRunning && step < maxSteps) {
                step++
                log("[Step $step]")

                // Capture
                val bmp = ScreenCaptureService.instance?.captureScreen()
                if (bmp == null) {
                    log("✗ Screenshot failed (svc=${ScreenCaptureService.instance != null}, ready=${ScreenCaptureService.isReady})")
                    break
                }
                log("📷 ${bmp.width}×${bmp.height}")

                // AI
                setStatus("Thinking...")
                val action = try {
                    GeminiHelper.analyze(
                        apiKey        = apiKey,
                        screenshot    = bmp,
                        goal          = goal,
                        history       = actionHistory.toString(),
                        originalWidth = bmp.width,
                        originalHeight= bmp.height
                    )
                } catch (e: Exception) {
                    log("✗ API: ${e.message}")
                    break
                }

                log("💭 ${action.thought}")
                log("▶ ${action.action} ${fmtParams(action)}")
                actionHistory.append("${action.action}(${fmtParams(action)}), ")
                setStatus(action.action)

                val a11y = AutomationAccessibilityService.instance
                when (action.action) {
                    "tap"   -> a11y?.performTap(action.x, action.y)
                    "swipe" -> a11y?.performSwipe(action.x, action.y, action.x2, action.y2)
                    "type"  -> a11y?.performType(action.text)
                    "wait"  -> Thread.sleep(1500)
                    "done"  -> {
                        log("✅ Task complete!")
                        mainHandler.post { stopAutomation() }
                        return@execute
                    }
                }
                Thread.sleep(1500)
            }

            if (step >= maxSteps) log("⚠ Max steps reached")
            mainHandler.post { stopAutomation() }
        }
    }

    private fun stopAutomation() {
        isRunning = false
        overlayManager.hide()
        btnRun.visibility  = Button.VISIBLE
        btnStop.visibility = Button.GONE
        setStatus("Idle")
        stopService(Intent(this, ScreenCaptureService::class.java))
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun fmtParams(a: GeminiHelper.AiAction) = when (a.action) {
        "tap"   -> "(${a.x},${a.y})"
        "swipe" -> "(${a.x},${a.y})→(${a.x2},${a.y2})"
        "type"  -> "\"${a.text}\""
        else    -> ""
    }

    private fun log(msg: String) = mainHandler.post {
        tvLog.append("$msg\n")
        scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun setStatus(s: String) = mainHandler.post {
        tvStatus.text = "Status: $s"
    }
}