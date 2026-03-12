package com.skiephone.app.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.skiephone.app.launcher.LauncherActivity
import com.skiephone.app.policy.SkieConfig

class SkieAccessibilityService : AccessibilityService() {

    private val whitelistedPackages = SkieConfig.whitelistedPackages

    override fun onServiceConnected() {
        super.onServiceConnected()
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOWS_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            notificationTimeout = 50
        }
        serviceInfo = info
        Log.d(TAG, "Accessibility service connected")
    }

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event?.keyCode == KeyEvent.KEYCODE_APP_SWITCH) {
            Log.d(TAG, "Recent apps button blocked")
            return true
        }
        return super.onKeyEvent(event)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString() ?: return

        // Detect and immediately close recents screen
        if (isRecentsPackage(packageName)) {
            Log.d(TAG, "Recents detected — going home")
            performGlobalAction(GLOBAL_ACTION_HOME)
            return
        }

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (!whitelistedPackages.contains(packageName)) {
                Log.d(TAG, "Unauthorized app: $packageName — redirecting")
                redirectToLauncher()
            }
        }
    }

    private fun isRecentsPackage(packageName: String): Boolean {
        // SystemUI hosts recents on stock Android and most OEMs
        if (packageName == "com.android.systemui") return true
        // OnePlus/Oppo specific
        if (packageName == "com.oneplus.launcher" ||
            packageName == "com.oppo.launcher" ||
            packageName == "com.realme.launcher") return true
        return false
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility service interrupted")
    }

    private fun redirectToLauncher() {
        val intent = Intent(this, LauncherActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        startActivity(intent)
    }

    companion object {
        private const val TAG = "SkieAccessibility"
    }
}