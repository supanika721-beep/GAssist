package com.aquille.gassist

import android.app.Activity
import android.content.Intent
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

    private lateinit var tvLog:      TextView
    private lateinit var tvStatus:   TextView
    private lateinit var etGoal:     EditText
    private lateinit var btnRun:     Button
    private lateinit var btnStop:    Button
    private lateinit var scrollView: ScrollView

    private val executor    = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var isRunning = false
    private val PROJECTION_REQUEST  = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvLog      = findViewById(R.id.tvLog)
        tvStatus   = findViewById(R.id.tvStatus)
        etGoal     = findViewById(R.id.etGoal)
        btnRun     = findViewById(R.id.btnRun)
        btnStop    = findViewById(R.id.btnStop)
        scrollView = findViewById(R.id.scrollView)

        btnRun.setOnClickListener  { startAutomation() }
        btnStop.setOnClickListener { stopAutomation() }
    }

    private fun startAutomation() {
        log("⏳ Starting Service...")
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
                log("✗ Activity Error: ${e.message}")
            }
        }, 1000)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PROJECTION_REQUEST) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                val initIntent = Intent(this, ScreenCaptureService::class.java).apply {
                    action = ScreenCaptureService.ACTION_INIT
                    putExtra(ScreenCaptureService.EXTRA_CODE, resultCode)
                    putExtra(ScreenCaptureService.EXTRA_DATA, data)
                }
                startService(initIntent)
                log("✅ Permission OK")
                mainHandler.postDelayed({ 
                    moveTaskToBack(true)
                    runLoop() 
                }, 1500)
            } else {
                log("✗ Denied")
            }
        }
    }

    private fun runLoop() {
        isRunning = true
        btnRun.visibility = Button.GONE
        btnStop.visibility = Button.VISIBLE
        
        executor.execute {
            while (isRunning) {
                val bmp = ScreenCaptureService.instance?.captureScreen()
                if (bmp == null) {
                    val err = ScreenCaptureService.lastError
                    log("✗ FAILED: $err")
                    // Jika error karena timeout, mungkin butuh refresh layar
                    Thread.sleep(2000)
                } else {
                    log("📷 Captured: ${bmp.width}x${bmp.height}")
                    // Lanjut alur AI Anda...
                    Thread.sleep(3000)
                }
            }
        }
    }

    private fun stopAutomation() {
        isRunning = false
        btnRun.visibility = Button.VISIBLE
        btnStop.visibility = Button.GONE
        stopService(Intent(this, ScreenCaptureService::class.java))
    }

    private fun log(msg: String) = mainHandler.post {
        tvLog.append("$msg\n")
        scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
    }
}