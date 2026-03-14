package com.skiephone.app.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

class SkieAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Minimal config — only used for future accessibility features
        // Recents blocking: handled by lock task mode (startLockTask) during focus
        // App blocking: handled by DPC setPackagesSuspended
        // Do NOT use FLAG_REQUEST_FILTER_KEY_EVENTS — blocks keyboard input
        val info = AccessibilityServiceInfo().apply {
            eventTypes = 0 // no events needed right now
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = 0
            notificationTimeout = 0
        }
        serviceInfo = info
        Log.d(TAG, "Accessibility service connected (passive mode)")
    }

    override fun onKeyEvent(event: KeyEvent?) = false

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    companion object { private const val TAG = "SkieAccessibility" }
}