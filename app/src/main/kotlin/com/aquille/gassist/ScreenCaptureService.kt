package com.aquille.gassist

import android.app.*
import android.content.pm.ServiceInfo
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
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ScreenCaptureService : Service() {

    companion object {
<<<<<<< HEAD
        const val CHANNEL_ID      = "gassist_capture"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
=======
        private const val TAG = "GAssist_Capture"
        const val CHANNEL_ID    = "gassist_capture"
        const val ACTION_INIT   = "init_projection"
        const val EXTRA_CODE    = "result_code"
        const val EXTRA_DATA    = "result_data"
>>>>>>> branch 'master' of https://github.com/supanika721-beep/GAssist.git

        var instance: ScreenCaptureService? = null
        @Volatile var isReady = false
        @Volatile var lastError: String = "None"
    }

    private var mediaProjection: MediaProjection? = null
<<<<<<< HEAD
    private var virtualDisplay: VirtualDisplay?   = null
    private var imageReader: ImageReader?          = null

    // HandlerThread agar ImageReader tidak di main thread (FIX ERROR 6)
    private lateinit var readerThread: HandlerThread
    private lateinit var readerHandler: Handler

    private var screenWidth  = 1080
    private var screenHeight = 2400
=======
    private var virtualDisplay:  VirtualDisplay?  = null
    private var imageReader:     ImageReader?      = null

    private val readerThread  = HandlerThread("GAssistReader").also { it.start() }
    private val readerHandler = Handler(readerThread.looper)

    private var screenW = 0
    private var screenH = 0
>>>>>>> branch 'master' of https://github.com/supanika721-beep/GAssist.git

    @Volatile
    private var isReady = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        isReady  = false
        lastError = "Service Created"
        createNotificationChannel()

        // Mulai HandlerThread sebelum apapun
        readerThread = HandlerThread("GAssistReader").also { it.start() }
        readerHandler = Handler(readerThread.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
<<<<<<< HEAD
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
=======
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
>>>>>>> branch 'master' of https://github.com/supanika721-beep/GAssist.git
        } else {
<<<<<<< HEAD
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
=======
            startForeground(1, notif)
>>>>>>> branch 'master' of https://github.com/supanika721-beep/GAssist.git
        }

<<<<<<< HEAD
        // FIX ERROR 9: Kondisi benar — resultCode != 0 artinya RESULT_OK (-1) lolos
        if (resultCode != 0 && resultData != null) {
            val metrics = resources.displayMetrics
            screenWidth  = metrics.widthPixels
            screenHeight = metrics.heightPixels
            setupProjection(resultCode, resultData)
=======
        if (intent?.action == ACTION_INIT) {
            // PERBAIKAN: Gunakan default value yang bukan RESULT_OK (-1)
            val code = intent.getIntExtra(EXTRA_CODE, 0) 
            val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(EXTRA_DATA)
            }
            
            // RESULT_OK adalah -1, jadi kita cek apakah code != 0
            if (code != 0 && data != null) {
                setupProjection(code, data)
            } else {
                lastError = "Init Data Invalid: code=$code"
            }
>>>>>>> branch 'master' of https://github.com/supanika721-beep/GAssist.git
        }

        return START_NOT_STICKY
    }

<<<<<<< HEAD
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
=======
    private fun setupProjection(code: Int, data: Intent) {
        try {
            val metrics = resources.displayMetrics
            screenW = metrics.widthPixels
            screenH = metrics.heightPixels

            val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            
            virtualDisplay?.release()
            mediaProjection?.stop()
            imageReader?.close()

            mediaProjection = pm.getMediaProjection(code, data)
            if (mediaProjection == null) {
                lastError = "MediaProjection NULL"
                return
            }

            // Gunakan maxImages=5 agar lebih stabil
            imageReader = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 5)

            imageReader!!.setOnImageAvailableListener({ _ ->
                isReady = true
                lastError = "Ready"
            }, readerHandler)

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "GAssistVD",
                screenW, screenH,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader!!.surface,
                null, readerHandler
            )
            
            lastError = "Waiting for first frame..."
        } catch (e: Exception) {
            lastError = "Setup Error: ${e.message}"
        }
>>>>>>> branch 'master' of https://github.com/supanika721-beep/GAssist.git
    }

    fun captureScreen(): Bitmap? {
<<<<<<< HEAD
        if (imageReader == null) return null   // guard null

        // Tunggu sampai frame pertama tersedia (maks 3 detik)
        val deadline = System.currentTimeMillis() + 3000
        while (!isReady && System.currentTimeMillis() < deadline) {
            Thread.sleep(100)
=======
        if (imageReader == null) {
            // Coba beri tahu user jika service belum di-init
            lastError = "Reader is NULL (Not initialized?)"
            return null
>>>>>>> branch 'master' of https://github.com/supanika721-beep/GAssist.git
        }
<<<<<<< HEAD
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
=======

        // Tunggu frame pertama
        val deadline = System.currentTimeMillis() + 5000L
        while (!isReady && System.currentTimeMillis() < deadline) {
            // Pancing frame
            readerHandler.post {
                try { imageReader?.acquireLatestImage()?.close() } catch (e: Exception) {}
            }
            Thread.sleep(200)
        }

        if (!isReady) {
            lastError = "Frame Timeout (Is screen static?)"
            return null
        }

        val latch = CountDownLatch(1)
        var result: Bitmap? = null

        readerHandler.post {
            try {
                val image = imageReader?.acquireLatestImage()
                if (image != null) {
                    try {
                        val planes = image.planes
                        val buffer = planes[0].buffer
                        val pixelStride = planes[0].pixelStride
                        val rowStride = planes[0].rowStride
                        val rowPadding = rowStride - pixelStride * screenW

                        val bitmap = Bitmap.createBitmap(
                            screenW + rowPadding / pixelStride,
                            screenH,
                            Bitmap.Config.ARGB_8888
                        )
                        bitmap.copyPixelsFromBuffer(buffer)
                        result = Bitmap.createBitmap(bitmap, 0, 0, screenW, screenH)
                    } finally {
                        image.close()
                    }
                }
            } catch (e: Exception) {
                lastError = "Capture Error: ${e.message}"
            } finally {
                latch.countDown()
            }
        }

        latch.await(2, TimeUnit.SECONDS)
        return result
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GAssist")
            .setContentText("Screen Capture Running")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
>>>>>>> branch 'master' of https://github.com/supanika721-beep/GAssist.git
            .build()
    }

    private fun createNotificationChannel() {
<<<<<<< HEAD
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Screen Capture",
            NotificationManager.IMPORTANCE_LOW
        ).apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
=======
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Capture", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
>>>>>>> branch 'master' of https://github.com/supanika721-beep/GAssist.git
    }

    override fun onDestroy() {
        isReady = false
        virtualDisplay?.release()
        mediaProjection?.stop()
        imageReader?.close()
        readerThread.quitSafely()   // FIX: selalu quit HandlerThread di onDestroy
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}