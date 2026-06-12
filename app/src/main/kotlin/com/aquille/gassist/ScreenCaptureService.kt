package com.aquille.gassist

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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

class ScreenCaptureService : Service() {

    companion object {
        const val CHANNEL_ID      = "gassist_capture"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        var instance: ScreenCaptureService? = null
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay?   = null
    private var imageReader: ImageReader?          = null

    // HandlerThread agar ImageReader tidak di main thread (FIX ERROR 6)
    private lateinit var readerThread: HandlerThread
    private lateinit var readerHandler: Handler

    private var screenWidth  = 1080
    private var screenHeight = 2400

    @Volatile
    private var isReady = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()

        // Mulai HandlerThread sebelum apapun
        readerThread = HandlerThread("GAssistReader").also { it.start() }
        readerHandler = Handler(readerThread.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()

        // startForeground WAJIB sebelum MediaProjection (FIX ERROR 5)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notification)
        }

        // FIX ERROR 9: Gunakan default 0, bukan -1
        // Activity.RESULT_OK == -1, jadi default -1 selalu lolos kondisi
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0

        // FIX ERROR 7: getParcelableExtra kompatibel API 33+
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        // FIX ERROR 9: Kondisi benar — resultCode != 0 artinya RESULT_OK (-1) lolos
        if (resultCode != 0 && resultData != null) {
            val metrics = resources.displayMetrics
            screenWidth  = metrics.widthPixels
            screenHeight = metrics.heightPixels
            setupProjection(resultCode, resultData)
        }

        return START_NOT_STICKY
    }

    private fun setupProjection(resultCode: Int, data: Intent) {
        val projManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projManager.getMediaProjection(resultCode, data)

        // Android 14+ perlu callback untuk register sebelum createVirtualDisplay
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {}, readerHandler)
        }

        setupVirtualDisplay()
    }

    private fun setupVirtualDisplay() {
        isReady = false
        imageReader = ImageReader.newInstance(
            screenWidth, screenHeight,
            PixelFormat.RGBA_8888, 2
        )

        // FIX ERROR 6: Listener di HandlerThread, bukan main thread
        imageReader!!.setOnImageAvailableListener(
            { _ -> isReady = true },
            readerHandler
        )

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "GAssistCapture",
            screenWidth, screenHeight,
            resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null, null
        )
    }

    fun captureScreen(): Bitmap? {
        if (imageReader == null) return null   // guard null

        // Tunggu sampai frame pertama tersedia (maks 3 detik)
        val deadline = System.currentTimeMillis() + 3000
        while (!isReady && System.currentTimeMillis() < deadline) {
            Thread.sleep(100)
        }
        if (!isReady) return null

        // Retry sampai 10x dengan jeda 200ms
        repeat(10) {
            val image = imageReader?.acquireLatestImage() ?: run {
                Thread.sleep(200)
                return@repeat
            }
            return try {
                val planes     = image.planes
                val buffer     = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride  = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * screenWidth

                val bmp = Bitmap.createBitmap(
                    screenWidth + rowPadding / pixelStride,
                    screenHeight,
                    Bitmap.Config.ARGB_8888
                )
                bmp.copyPixelsFromBuffer(buffer)
                Bitmap.createBitmap(bmp, 0, 0, screenWidth, screenHeight)
            } finally {
                image.close()
            }
        }
        return null
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GAssist")
            .setContentText("AI automation active")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Screen Capture",
            NotificationManager.IMPORTANCE_LOW
        ).apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        virtualDisplay?.release()
        mediaProjection?.stop()
        imageReader?.close()
        readerThread.quitSafely()   // FIX: selalu quit HandlerThread di onDestroy
        instance = null
        super.onDestroy()
    }
}
