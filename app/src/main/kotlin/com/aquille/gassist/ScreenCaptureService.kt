package com.aquille.gassist

import android.app.*
import android.content.pm.ServiceInfo
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
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ScreenCaptureService"
        const val CHANNEL_ID    = "gassist_capture"
        const val ACTION_INIT   = "init_projection"
        const val EXTRA_CODE    = "result_code"
        const val EXTRA_DATA    = "result_data"

        var instance: ScreenCaptureService? = null
        @Volatile var isReady = false
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay:  VirtualDisplay?  = null
    private var imageReader:     ImageReader?      = null

    private val readerThread  = HandlerThread("GAssistReader").also { it.start() }
    private val readerHandler = Handler(readerThread.looper)

    private var screenW = 0
    private var screenH = 0

    override fun onCreate() {
        super.onCreate()
        instance = this
        isReady  = false
        createNotificationChannel()
        Log.d(TAG, "Service Created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notif)
        }

        if (intent?.action == ACTION_INIT) {
            val code = intent.getIntExtra(EXTRA_CODE, -1)
            val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(EXTRA_DATA)
            }
            if (code != -1 && data != null) {
                setupProjection(code, data)
            } else {
                Log.e(TAG, "Invalid projection data received")
            }
        }

        return START_NOT_STICKY
    }

    private fun setupProjection(code: Int, data: Intent) {
        Log.d(TAG, "Setting up projection...")
        val metrics = resources.displayMetrics
        screenW = metrics.widthPixels
        screenH = metrics.heightPixels

        val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        
        // Cleanup existing projection if any
        virtualDisplay?.release()
        mediaProjection?.stop()
        imageReader?.close()

        mediaProjection = pm.getMediaProjection(code, data)
        
        // Gunakan PixelFormat.RGBA_8888 dan pastikan maxImages cukup (3-5)
        imageReader = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 5)

        imageReader!!.setOnImageAvailableListener({ _ ->
            if (!isReady) {
                Log.d(TAG, "First frame received, service is now ready")
                isReady = true
            }
        }, readerHandler)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "GAssistVD",
            screenW, screenH,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            object : VirtualDisplay.Callback() {
                override fun onPaused() { Log.d(TAG, "VirtualDisplay Paused") }
                override fun onResumed() { Log.d(TAG, "VirtualDisplay Resumed") }
                override fun onStopped() { Log.d(TAG, "VirtualDisplay Stopped") }
            }, 
            readerHandler
        )
    }

    fun captureScreen(): Bitmap? {
        // Jika belum ready, tunggu sebentar (mungkin layar sedang statis)
        if (!isReady) {
            Log.w(TAG, "Capture requested but not ready, waiting...")
            val deadline = System.currentTimeMillis() + 3_000L
            while (!isReady && System.currentTimeMillis() < deadline) {
                // Coba "pancing" dengan acquireLatestImage meski isReady false
                val img = imageReader?.acquireLatestImage()
                if (img != null) {
                    img.close()
                    isReady = true
                    break
                }
                Thread.sleep(200)
            }
        }

        if (imageReader == null) {
            Log.e(TAG, "Capture failed: ImageReader is null")
            return null
        }

        val latch = CountDownLatch(1)
        var result: Bitmap? = null

        readerHandler.post {
            try {
                // Gunakan acquireLatestImage untuk mendapatkan frame paling baru
                val image = imageReader?.acquireLatestImage() ?: imageReader?.acquireNextImage()
                
                if (image != null) {
                    try {
                        val planes = image.planes
                        val buffer = planes[0].buffer
                        val pixelStride = planes[0].pixelStride
                        val rowStride = planes[0].rowStride
                        val rowPadding = rowStride - pixelStride * screenW

                        // Buat bitmap dengan mempertimbangkan padding
                        val bitmap = Bitmap.createBitmap(
                            screenW + rowPadding / pixelStride,
                            screenH,
                            Bitmap.Config.ARGB_8888
                        )
                        bitmap.copyPixelsFromBuffer(buffer)
                        
                        // Crop ke ukuran layar asli
                        result = Bitmap.createBitmap(bitmap, 0, 0, screenW, screenH)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing image: ${e.message}")
                    } finally {
                        image.close()
                    }
                } else {
                    Log.e(TAG, "acquireLatestImage returned null")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Capture error: ${e.message}")
            } finally {
                latch.countDown()
            }
        }

        try {
            latch.await(2, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Log.e(TAG, "Latch interrupted")
        }
        
        return result
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GAssist")
            .setContentText("Screen capture is active")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Screen Capture", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "Service Destroying")
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