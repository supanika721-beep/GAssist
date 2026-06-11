package com.aquille.gassist

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Menampilkan overlay semi-transparan "AI is working" di atas layar saat AI sedang beroperasi.
 * Memblokir sentuhan user agar tidak mengganggu aksi AI.
 * Requires SYSTEM_ALERT_WINDOW permission.
 */
class OverlayManager(private val context: Context) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: LinearLayout? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    var onStopClicked: (() -> Unit)? = null

    fun show() {
        mainHandler.post {
            if (overlayView != null) return@post

            // Root container — dim seluruh layar
            val root = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundColor(Color.argb(180, 0, 0, 0)) // hitam 70% transparan
            }

            // Teks "AI is working"
            val tv = TextView(context).apply {
                text = "🤖 AI is working..."
                textSize = 20f
                setTextColor(Color.parseColor("#00D4FF"))
                typeface = android.graphics.Typeface.MONOSPACE
                gravity = Gravity.CENTER
            }

            // Tombol STOP
            val btn = Button(context).apply {
                text = "■ STOP"
                setTextColor(Color.parseColor("#0A0E14"))
                backgroundTintList = android.content.res.ColorStateList.valueOf(
                    Color.parseColor("#FF4444")
                )
                typeface = android.graphics.Typeface.MONOSPACE
                val pad = dpToPx(48)
                val btnParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dpToPx(24) }
                layoutParams = btnParams
                setOnClickListener {
                    onStopClicked?.invoke()
                    hide()
                }
            }

            root.addView(tv)
            root.addView(btn)
            overlayView = root

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                // FLAG_NOT_FOCUSABLE dihapus agar tombol bisa diklik
                // FLAG_NOT_TOUCH_MODAL dihapus agar sentuhan di luar tombol terblokir
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.CENTER

            try {
                wm.addView(root, params)
            } catch (e: Exception) {
                overlayView = null
            }
        }
    }

    fun hide() {
        mainHandler.post {
            overlayView?.let {
                try { wm.removeView(it) } catch (_: Exception) {}
                overlayView = null
            }
        }
    }

    private fun dpToPx(dp: Int): Int =
        (dp * context.resources.displayMetrics.density).toInt()
}