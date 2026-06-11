package com.aquille.gassist

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var tvLog:      TextView
    private lateinit var tvStatus:   TextView
    private lateinit var etGoal:     EditText
    private lateinit var btnRun:     Button
    private lateinit var btnStop:    Button
    private lateinit var btnSettings: Button
    private lateinit var scrollView: ScrollView

    private lateinit var settingsManager: SettingsManager
    private lateinit var overlayManager:  OverlayManager

    private val executor    = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var isRunning     = false
    private val actionHistory           = StringBuilder()
    private val PROJECTION_REQUEST      = 100
    private val OVERLAY_PERMISSION_REQ  = 101

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

        runStartupChecks()
    }

    private fun runStartupChecks() {
        if (!NetworkUtils.isInternetAvailable(this)) {
            showNoInternetDialog()
            return
        }
        if (!isAccessibilityEnabled()) {
            showAccessibilityDialog()
            return
        }
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
            .setMessage("GAssist requires an active internet connection to work.")
            .setCancelable(false)
            .setPositiveButton("OK") { _, _ -> finish() }
            .show()
    }

    private fun showAccessibilityDialog() {
        AlertDialog.Builder(this)
            .setTitle("Accessibility Permission Required")
            .setMessage("GAssist needs Accessibility Service access to control your screen.")
            .setCancelable(false)
            .setPositiveButton("OK") { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .show()
    }

    override fun onResume() {
        super.onResume()
        if (!isRunning) {
            val logText = tvLog.text.toString()
            if (logText.isEmpty()) {
                runStartupChecks()
            } else if (!logText.contains("[GAssist Ready]") && isAccessibilityEnabled()) {
                log("[GAssist Ready]")
                setStatus("Idle")
            }
        }
    }

    private fun startAutomation() {
        val goal = etGoal.text.toString().trim()
        if (goal.isEmpty()) { log("⚠ Enter a goal first"); return }
        if (!NetworkUtils.isInternetAvailable(this)) { showNoInternetDialog(); return }
        if (!settingsManager.hasApiKey()){ log("⚠ API Key not set!"); return }
        if (!isAccessibilityEnabled())   { showAccessibilityDialog(); return }

        if (!Settings.canDrawOverlays(this)) {
            AlertDialog.Builder(this)
                .setTitle("Overlay Permission Needed")
                .setMessage("GAssist needs 'Appear on top' permission.")
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
        // Pastikan service dimulai sebagai foreground sebelum request projection (Syarat Android 14)
        val serviceIntent = Intent(this, ScreenCaptureService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        mainHandler.postDelayed({
            val pm = getSystemService(MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
            try {
                startActivityForResult(pm.createScreenCaptureIntent(), PROJECTION_REQUEST)
            } catch (e: Exception) {
                log("✗ Failed to start projection: ${e.message}")
            }
        }, 800) // Sedikit ditambah jedanya agar lebih aman
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            OVERLAY_PERMISSION_REQ -> proceedToCapture()
            PROJECTION_REQUEST -> {
                if (resultCode == Activity.RESULT_OK && data != null) {
                    val initIntent = Intent(this, ScreenCaptureService::class.java).apply {
                        action = ScreenCaptureService.ACTION_INIT
                        putExtra(ScreenCaptureService.EXTRA_CODE, resultCode)
                        putExtra(ScreenCaptureService.EXTRA_DATA, data)
                    }
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        startForegroundService(initIntent)
                    } else {
                        startService(initIntent)
                    }
                    log("✅ Permission granted")

                    log("📱 Minimizing...")
                    mainHandler.postDelayed({
                        moveTaskToBack(true)
                        // Beri waktu lebih lama setelah minimize agar layar refresh
                        mainHandler.postDelayed({ runLoop() }, 1500)
                    }, 1000)
                } else {
                    log("✗ Screen capture denied")
                    stopService(Intent(this, ScreenCaptureService::class.java))
                }
            }
        }
    }

    private fun runLoop() {
        if (isRunning) return
        val goal   = etGoal.text.toString().trim()
        val apiKey = settingsManager.getApiKey()
        isRunning  = true
        actionHistory.clear()

        if (Settings.canDrawOverlays(this)) overlayManager.show()

        mainHandler.post {
            btnRun.visibility  = Button.GONE
            btnStop.visibility = Button.VISIBLE
            setStatus("Running...")
        }

        executor.execute {
            try {
                var step = 0
                val maxSteps = 20

                while (isRunning && step < maxSteps) {
                    step++
                    log("[Step $step]")

                    // Capture dengan retry logic di dalam service
                    val bmp = ScreenCaptureService.instance?.captureScreen()
                    if (bmp == null) {
                        val svcActive = ScreenCaptureService.instance != null
                        val isReady = ScreenCaptureService.isReady
                        log("✗ Screenshot failed (svc=$svcActive, ready=$isReady)")
                        // Jika gagal, coba tunggu sebentar lagi sebelum menyerah
                        Thread.sleep(2000)
                        val retryBmp = ScreenCaptureService.instance?.captureScreen()
                        if (retryBmp == null) break else { /* continue with retryBmp */ }
                    }
                    
                    val currentBmp = bmp ?: continue
                    log("📷 ${currentBmp.width}×${currentBmp.height}")

                    setStatus("Thinking...")
                    val action = try {
                        GeminiHelper.analyze(
                            apiKey        = apiKey,
                            screenshot    = currentBmp,
                            goal          = goal,
                            history       = actionHistory.toString(),
                            originalWidth = currentBmp.width,
                            originalHeight= currentBmp.height
                        )
                    } catch (e: Exception) {
                        log("✗ API Error: ${e.message}")
                        break
                    }

                    log("💭 ${action.thought}")
                    log("▶ ${action.action}")
                    actionHistory.append("${action.action}, ")
                    setStatus(action.action)

                    val a11y = AutomationAccessibilityService.instance
                    if (a11y == null) {
                        log("⚠ Accessibility Service not running!")
                        break
                    }

                    when (action.action) {
                        "tap"   -> a11y.performTap(action.x, action.y)
                        "swipe" -> a11y.performSwipe(action.x, action.y, action.x2, action.y2)
                        "type"  -> a11y.performType(action.text)
                        "wait"  -> Thread.sleep(2000)
                        "done"  -> {
                            log("✅ Task complete!")
                            break
                        }
                    }
                    Thread.sleep(2000)
                }
            } catch (e: Exception) {
                log("✗ Loop Error: ${e.message}")
            } finally {
                mainHandler.post { stopAutomation() }
            }
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

    private fun log(msg: String) = mainHandler.post {
        tvLog.append("$msg\n")
        scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
        Log.d("GAssist", msg)
    }

    private fun setStatus(s: String) = mainHandler.post {
        tvStatus.text = "Status: $s"
    }
}