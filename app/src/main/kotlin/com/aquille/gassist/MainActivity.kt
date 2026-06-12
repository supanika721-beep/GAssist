package com.aquille.gassist

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var tvLog: TextView
    private lateinit var tvStatusChip: TextView
    private lateinit var tvStep: TextView
    private lateinit var tvStatusDetail: TextView
    private lateinit var etGoal: EditText
    private lateinit var btnRun: TextView
    private lateinit var btnStop: TextView
    private lateinit var btnSettings: TextView
    private lateinit var btnCopyLog: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var progressBar: ProgressBar
    private lateinit var rowStepInfo: LinearLayout
    private lateinit var settingsManager: SettingsManager

    private val executor    = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isRunning   = false
    private var actionHistory = StringBuilder()

    private val PROJECTION_REQUEST = 100

    // ── Network callback — monitor internet continuously ──────────────────────
    private var isInternetConnected = false
    private lateinit var connectivityManager: ConnectivityManager
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            isInternetConnected = true
        }
        override fun onLost(network: Network) {
            isInternetConnected = false
            if (isRunning) {
                mainHandler.post {
                    showNoInternetDialog()
                    stopAutomation()
                }
            }
        }
    }

    // ── onCreate ──────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        settingsManager = SettingsManager(this)

        tvLog          = findViewById(R.id.tvLog)
        tvStatusChip   = findViewById(R.id.tvStatusChip)
        tvStep         = findViewById(R.id.tvStep)
        tvStatusDetail = findViewById(R.id.tvStatusDetail)
        etGoal         = findViewById(R.id.etGoal)
        btnRun         = findViewById(R.id.btnRun)
        btnStop        = findViewById(R.id.btnStop)
        btnSettings    = findViewById(R.id.btnSettings)
        btnCopyLog     = findViewById(R.id.btnCopyLog)
        scrollView     = findViewById(R.id.scrollView)
        progressBar    = findViewById(R.id.progressBar)
        rowStepInfo    = findViewById(R.id.rowStepInfo)

        // Make log selectable
        tvLog.setTextIsSelectable(true)
        tvLog.text = ""

        // Register continuous network monitor
        connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, networkCallback)

        // Seed initial internet state
        isInternetConnected = checkInternetNow()

        btnRun.setOnClickListener      { startAutomation() }
        btnStop.setOnClickListener     { stopAutomation() }
        btnSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        btnCopyLog.setOnClickListener  { copyLogToClipboard() }

        // Check accessibility on launch
        checkAccessibilityOnLaunch()
    }

    override fun onResume() {
        super.onResume()
        if (!isRunning) checkReadiness()
    }

    override fun onDestroy() {
        super.onDestroy()
        connectivityManager.unregisterNetworkCallback(networkCallback)
    }

    // ── Accessibility check on launch (once) ─────────────────────────────────

    private fun checkAccessibilityOnLaunch() {
        // Post slightly delayed so UI is fully drawn first
        mainHandler.postDelayed({
            if (AutomationAccessibilityService.instance == null) {
                showAccessibilityDialog()
            }
        }, 300)
    }

    // ── Readiness check ───────────────────────────────────────────────────────

    private fun checkReadiness() {
        val hasKey           = settingsManager.hasApiKey()
        val hasAccessibility = AutomationAccessibilityService.instance != null

        tvLog.text = ""

        when {
            !hasKey && !hasAccessibility -> {
                setChip("Setup needed", "#E07C3E")
                logSection("SETUP REQUIRED")
                logWarn("API Key not set")
                logInfo("Open ⚙ Settings → paste your Gemini API Key")
                logDivider()
                logWarn("Accessibility Service disabled")
                logInfo("Settings › Accessibility › GAssist › ON")
            }
            !hasKey -> {
                setChip("API Key missing", "#E07C3E")
                logSection("SETUP REQUIRED")
                logWarn("API Key not set")
                logInfo("Open ⚙ Settings → paste your Gemini API Key")
            }
            !hasAccessibility -> {
                setChip("Accessibility off", "#E07C3E")
                logSection("SETUP REQUIRED")
                logWarn("Accessibility Service disabled")
                logInfo("Settings › Accessibility › GAssist › ON")
            }
            else -> {
                setChip("Ready", "#4CAF82")
                logSection("GASSIST")
                logOk("API Key found")
                logOk("Accessibility Service active")
                logDivider()
                logInfo("Type a goal and press ▶ to start")
            }
        }
    }

    // ── Start automation ──────────────────────────────────────────────────────

    private fun startAutomation() {
        val goal = etGoal.text.toString().trim()

        if (goal.isEmpty()) {
            logWarn("Enter a goal first")
            return
        }
        if (!isInternetConnected) {
            showNoInternetDialog()
            return
        }
        if (!settingsManager.hasApiKey()) {
            logWarn("API Key not set — open ⚙ Settings")
            return
        }
        if (AutomationAccessibilityService.instance == null) {
            showAccessibilityDialog()
            return
        }

        val projManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(projManager.createScreenCaptureIntent(), PROJECTION_REQUEST)
    }

    // ── Dialogs ───────────────────────────────────────────────────────────────

    private fun showAccessibilityDialog() {
        AlertDialog.Builder(this, R.style.DialogTheme)
            .setTitle("Accessibility Permission Required")
            .setMessage("GAssist needs Accessibility Service permission to control the screen and perform automation.\n\nEnable it now?")
            .setPositiveButton("Open Settings") { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton("Exit") { _, _ ->
                finishAffinity()
            }
            .setCancelable(false)
            .show()
    }

    private fun showNoInternetDialog() {
        AlertDialog.Builder(this, R.style.DialogTheme)
            .setTitle("No Internet Connection")
            .setMessage("GAssist requires an internet connection to reach Gemini AI.\n\nCheck your connection and try again.")
            .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
            .setCancelable(true)
            .show()
    }

    // ── Network helpers ───────────────────────────────────────────────────────

    private fun checkInternetNow(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val caps    = connectivityManager.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
               caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    // ── onActivityResult ──────────────────────────────────────────────────────

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PROJECTION_REQUEST && resultCode == Activity.RESULT_OK && data != null) {
            val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
            }
            startForegroundService(serviceIntent)
            logInfo("Starting screen capture service…")
            mainHandler.postDelayed({ runAutomationLoop() }, 2000)
        }
    }

    // ── Automation loop ───────────────────────────────────────────────────────

    private fun runAutomationLoop() {
        val goal   = etGoal.text.toString().trim()
        val apiKey = settingsManager.getApiKey()
        isRunning  = true
        actionHistory.clear()

        mainHandler.post {
            btnRun.visibility      = View.GONE
            btnStop.visibility     = View.VISIBLE
            rowStepInfo.visibility = View.VISIBLE
            progressBar.visibility = View.VISIBLE
            progressBar.progress   = 0
            setChip("Running", "#7C6EE6")
            tvLog.text = ""
            logSection("RUNNING  ·  $goal")
        }

        executor.execute {
            var stepCount = 0
            val maxSteps  = 20

            while (isRunning && stepCount < maxSteps) {
                stepCount++
                updateStep(stepCount, maxSteps, "Capturing…")
                mainHandler.post { logDivider() }
                logStep(stepCount)

                val screenshot = ScreenCaptureService.instance?.captureScreen()
                if (screenshot == null) {
                    val alive = ScreenCaptureService.instance != null
                    logErr("Screenshot failed  (service alive: $alive)")
                    break
                }
                logOk("Screenshot  ${screenshot.width}×${screenshot.height}")

                updateStep(stepCount, maxSteps, "Thinking…")
                logInfo("Sending to Gemini…")

                val action = try {
                    GeminiHelper.analyze(
                        apiKey         = apiKey,
                        screenshot     = screenshot,
                        goal           = goal,
                        history        = actionHistory.toString(),
                        originalWidth  = screenshot.width,
                        originalHeight = screenshot.height
                    )
                } catch (e: Exception) {
                    val msg = e.message ?: "Unknown error"
                    when {
                        msg.contains("429") ->
                            logErr("Rate limit hit — wait a moment and try again")
                        msg.contains("401") || msg.contains("403") ->
                            logErr("Invalid API Key — check ⚙ Settings")
                        msg.contains("timeout", ignoreCase = true) ->
                            logErr("Request timed out — check internet connection")
                        else ->
                            logErr("API Error: $msg")
                    }
                    // Stop loop on API error, do NOT crash
                    mainHandler.post { stopAutomation() }
                    return@execute
                }

                logThought(action.thought)
                logAction(action)
                actionHistory.append("${action.action}(${formatParams(action)}), ")

                updateStep(stepCount, maxSteps, "Acting…")
                val acc = AutomationAccessibilityService.instance
                when (action.action) {
                    "tap"   -> acc?.performTap(action.x, action.y)
                    "swipe" -> acc?.performSwipe(action.x, action.y, action.x2, action.y2)
                    "type"  -> acc?.performType(action.text)
                    "wait"  -> Thread.sleep(1500)
                    "done"  -> {
                        mainHandler.post { logDivider() }
                        logOk("Task complete!")
                        mainHandler.post { stopAutomation() }
                        return@execute
                    }
                }
                Thread.sleep(1500)
            }

            if (isRunning && stepCount >= maxSteps) logWarn("Max steps (20) reached")
            mainHandler.post { stopAutomation() }
        }
    }

    private fun stopAutomation() {
        isRunning = false
        btnRun.visibility      = View.VISIBLE
        btnStop.visibility     = View.GONE
        rowStepInfo.visibility = View.GONE
        progressBar.visibility = View.GONE
        stopService(Intent(this, ScreenCaptureService::class.java))
        checkReadiness()
    }

    // ── Copy log ──────────────────────────────────────────────────────────────

    private fun copyLogToClipboard() {
        val text = tvLog.text.toString()
        if (text.isBlank()) {
            Toast.makeText(this, "Log is empty", Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("GAssist Log", text))
        Toast.makeText(this, "Log copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    // ── Logger ────────────────────────────────────────────────────────────────

    private fun logRaw(html: String) {
        mainHandler.post {
            tvLog.append(android.text.Html.fromHtml(
                "$html<br>",
                android.text.Html.FROM_HTML_MODE_LEGACY
            ))
            scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun logSection(title: String) =
        logRaw("<font color='#7C6EE6'><b>▸ $title</b></font>")

    private fun logStep(n: Int) =
        logRaw("<font color='#9B97B8'>── Step $n ──────────────</font>")

    private fun logDivider() =
        logRaw("<font color='#2E2B4A'>────────────────────────</font>")

    private fun logOk(msg: String) =
        logRaw("<font color='#4CAF82'>✓  $msg</font>")

    private fun logWarn(msg: String) =
        logRaw("<font color='#E0A030'>⚠  $msg</font>")

    private fun logErr(msg: String) =
        logRaw("<font color='#E05555'>✗  $msg</font>")

    private fun logInfo(msg: String) =
        logRaw("<font color='#C8C0F0'>   $msg</font>")

    private fun logThought(thought: String) =
        logRaw("<font color='#9B97B8'>💭 $thought</font>")

    private fun logAction(a: GeminiHelper.AiAction) {
        val label = when (a.action) {
            "tap"   -> "<font color='#7C6EE6'><b>TAP</b></font> <font color='#C8C0F0'>(${a.x}, ${a.y})</font>"
            "swipe" -> "<font color='#7C6EE6'><b>SWIPE</b></font> <font color='#C8C0F0'>(${a.x},${a.y}) → (${a.x2},${a.y2})</font>"
            "type"  -> "<font color='#7C6EE6'><b>TYPE</b></font> <font color='#C8C0F0'>\"${a.text}\"</font>"
            "wait"  -> "<font color='#9B97B8'><b>WAIT</b></font>"
            "done"  -> "<font color='#4CAF82'><b>DONE</b></font>"
            else    -> "<font color='#C8C0F0'><b>${a.action.uppercase()}</b></font>"
        }
        logRaw("▶  $label")
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun formatParams(a: GeminiHelper.AiAction) = when (a.action) {
        "tap"   -> "(${a.x}, ${a.y})"
        "swipe" -> "(${a.x}, ${a.y}) → (${a.x2}, ${a.y2})"
        "type"  -> "\"${a.text}\""
        else    -> ""
    }

    private fun updateStep(step: Int, max: Int, detail: String) {
        mainHandler.post {
            tvStep.text          = "Step $step / $max"
            tvStatusDetail.text  = detail
            progressBar.progress = step
        }
    }

    private fun setChip(label: String, colorHex: String) {
        mainHandler.post {
            tvStatusChip.text = label
            tvStatusChip.setTextColor(android.graphics.Color.parseColor(colorHex))
        }
    }
}
