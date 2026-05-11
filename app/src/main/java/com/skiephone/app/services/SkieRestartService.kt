package com.skiephone.app.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.skiephone.app.admin.SkieDeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.util.Log
import android.util.TypedValue

/**
 * SkieRestartService
 *
 * Foreground service that draws a full-screen TYPE_APPLICATION_OVERLAY window
 * over all apps — no dependency on LauncherActivity or any foreground activity.
 *
 * Shows a solid blue countdown overlay (30s) with:
 *   - Skie branding (app name + tagline)
 *   - "Device restart required" message
 *   - Live countdown timer
 *   - "Restart Now" CTA — immediately calls dpm.reboot()
 *
 * Command node is cleared by SkieCommandManager BEFORE this service starts,
 * so the stale command guard prevents re-execution after reboot.
 */
class SkieRestartService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var overlayView: LinearLayout? = null
    private var countdownText: TextView? = null
    private var secondsRemaining = COUNTDOWN_SECONDS
    private val dpm by lazy {
        getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    }
    private val adminComponent by lazy {
        ComponentName(this, SkieDeviceAdminReceiver::class.java)
    }

    companion object {
        private const val TAG = "SkieRestartService"
        private const val COUNTDOWN_SECONDS = 30
        private const val CHANNEL_ID = "skie_restart"
        private const val NOTIF_ID = 1003

        // Skie brand blue
        private const val SKIE_BLUE = 0xFF1B6CF7.toInt()
    }

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()
        showOverlay()
    }

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Skie Restart", NotificationManager.IMPORTANCE_LOW
            ).apply { setSound(null, null); setShowBadge(false) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SkieOS Restart")
            .setContentText("Device is restarting…")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build()
        startForeground(NOTIF_ID, notif)
    }

    private fun showOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // ── Root container — solid Skie blue, full screen ──────────────────
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(SKIE_BLUE)
            setPadding(dp(32), dp(48), dp(32), dp(48))
        }

        // ── App name ───────────────────────────────────────────────────────
        val appName = TextView(this).apply {
            text = "SkieOS"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 36f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
        }

        // ── Tagline ────────────────────────────────────────────────────────
        val tagline = TextView(this).apply {
            text = "Managed Device"
            setTextColor(Color.parseColor("#B3FFFFFF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(40))
        }

        // ── Divider ────────────────────────────────────────────────────────
        val divider = android.view.View(this).apply {
            setBackgroundColor(Color.parseColor("#33FFFFFF"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
            ).also { it.bottomMargin = dp(40) }
        }

        // ── Title ──────────────────────────────────────────────────────────
        val title = TextView(this).apply {
            text = "Device Restart Required"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(12))
        }

        // ── Subtitle ───────────────────────────────────────────────────────
        val subtitle = TextView(this).apply {
            text = "Your administrator has initiated a system restart.\nPlease save any open work."
            setTextColor(Color.parseColor("#CCFFFFFF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(40))
        }

        // ── Countdown label ────────────────────────────────────────────────
        val countdownLabel = TextView(this).apply {
            text = "Restarting in"
            setTextColor(Color.parseColor("#B3FFFFFF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
        }

        // ── Countdown number ───────────────────────────────────────────────
        countdownText = TextView(this).apply {
            text = secondsRemaining.toString()
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 72f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(8))
        }

        // ── Seconds label ──────────────────────────────────────────────────
        val secondsLabel = TextView(this).apply {
            text = "seconds"
            setTextColor(Color.parseColor("#B3FFFFFF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(48))
        }

        // ── Restart Now button ─────────────────────────────────────────────
        val restartBtn = Button(this).apply {
            text = "Restart Now"
            setTextColor(SKIE_BLUE)
            setBackgroundColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(52)
            ).also { it.topMargin = dp(0) }
            setOnClickListener { performReboot() }
        }

        root.addView(appName)
        root.addView(tagline)
        root.addView(divider)
        root.addView(title)
        root.addView(subtitle)
        root.addView(countdownLabel)
        root.addView(countdownText)
        root.addView(secondsLabel)
        root.addView(restartBtn)

        overlayView = root

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_ALERT

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        // Remove NOT_TOUCHABLE so button works — re-add NOT_FOCUSABLE to avoid
        // pulling focus from system UI but still allow touch on our view
        params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()

        try {
            windowManager?.addView(root, params)
            startCountdown()
            Log.d(TAG, "Overlay shown — countdown started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add overlay view: ${e.message}")
            // Fall back to immediate reboot if overlay fails
            handler.postDelayed({ performReboot() }, 3000)
        }
    }

    private fun startCountdown() {
        val tick = object : Runnable {
            override fun run() {
                secondsRemaining--
                countdownText?.text = secondsRemaining.toString()
                if (secondsRemaining <= 0) {
                    performReboot()
                } else {
                    handler.postDelayed(this, 1000)
                }
            }
        }
        handler.postDelayed(tick, 1000)
    }

    private fun performReboot() {
        handler.removeCallbacksAndMessages(null)
        Log.d(TAG, "Rebooting device via DPM")
        try {
            if (dpm.isDeviceOwnerApp(packageName)) {
                dpm.reboot(adminComponent)
            } else {
                Log.e(TAG, "Cannot reboot — not device owner")
                dismissOverlay()
                stopSelf()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Reboot failed: ${e.message}")
            dismissOverlay()
            stopSelf()
        }
    }

    private fun dismissOverlay() {
        try {
            overlayView?.let { windowManager?.removeView(it) }
        } catch (e: Exception) { /* already removed */ }
        overlayView = null
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        dismissOverlay()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}