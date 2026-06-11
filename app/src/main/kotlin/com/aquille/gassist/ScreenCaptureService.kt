package com.aquille.gassist

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ScreenCaptureService : Service() {

    companion object {
        const val CHANNEL_ID    = "gassist_capture"
        const val ACTION_INIT   = "init_projection"
        const val EXTRA_CODE    = "result_code"
        const val EXTRA_DATA    = "result_data"

        var instance: ScreenCaptureService? = null
        // true setelah VirtualDisplay terbentuk dan frame pertama sudah masuk
        @Volatile var isReady = false
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay:  VirtualDisplay?  = null
    private var imageReader:     ImageReader?      = null

    // Background thread khusus untuk ImageReader — wajib, jangan pakai main thread
    private val readerThread  = HandlerThread("GAssistReader").also { it.start() }
    private val readerHandler = Handler(readerThread.looper)

    private var screenW = 0
    private var screenH = 0

    // ──────────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        instance = this
        isReady  = false
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 1. startForeground PERTAMA — wajib Android 14+ sebelum apapun
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notif)
        }

        // 2. Kalau intent bawa data projection, setup sekarang
        if (intent?.action == ACTION_INIT) {
            val code = intent.getIntExtra(EXTRA_CODE, -1)
            val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(EXTRA_DATA)
            }
            if (code != -1 && data != null) setupProjection(code, data)
        }

        return START_NOT_STICKY
    }

    // ──────────────────────────────────────────────
    // Setup
    // ──────────────────────────────────────────────

    private fun setupProjection(code: Int, data: Intent) {
        val metrics = resources.displayMetrics
        screenW = metrics.widthPixels
        screenH = metrics.heightPixels

        val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = pm.getMediaProjection(code, data)

        // ImageReader dengan maxImages=3 supaya tidak "maxImages acquired" error
        imageReader = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 3)

        // Listener di background thread — JANGAN main thread (crash)
        imageReader!!.setOnImageAvailableListener({ _ ->
            isReady = true   // frame pertama sudah masuk, siap di-acquire
        }, readerHandler)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "GAssistVD",
            screenW, screenH,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null, null
        )
    }

    // ──────────────────────────────────────────────
    // Capture — dipanggil dari background thread executor di MainActivity
    // ──────────────────────────────────────────────

    fun captureScreen(): Bitmap? {
        // Tunggu frame pertama tersedia, max 6 detik
        val deadline = System.currentTimeMillis() + 6_000L
        while (!isReady && System.currentTimeMillis() < deadline) {
            Thread.sleep(100)
        }
        if (!isReady) return null

        // Pakai latch agar kita bisa blok thread pemanggil sampai frame benar-benar ada
        val latch  = CountDownLatch(1)
        var result: Bitmap? = null

        readerHandler.post {
            val image = imageReader?.acquireLatestImage()
            if (image != null) {
                try {
                    val plane      = image.planes[0]
                    val buf        = plane.buffer
                    val pxStride   = plane.pixelStride
                    val rowStride  = plane.rowStride
                    val rowPad     = rowStride - pxStride * screenW

                    val bmp = Bitmap.createBitmap(
                        screenW + rowPad / pxStride,
                        screenH,
                        Bitmap.Config.ARGB_8888
                    )
                    bmp.copyPixelsFromBuffer(buf)
                    result = Bitmap.createBitmap(bmp, 0, 0, screenW, screenH)
                } finally {
                    image.close()
                }
            }
            latch.countDown()
        }

        latch.await(3, TimeUnit.SECONDS)
        return result
    }

    // ──────────────────────────────────────────────
    // Notification & Channel
    // ──────────────────────────────────────────────

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GAssist")
            .setContentText("Screen capture active")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "Screen Capture", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    // ──────────────────────────────────────────────
    // Cleanup
    // ──────────────────────────────────────────────

    override fun onDestroy() {
        isReady = false
        virtualDisplay?.release()
        mediaProjection?.stop()
        imageReader?.close()
        readerThread.quitSafely()
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}