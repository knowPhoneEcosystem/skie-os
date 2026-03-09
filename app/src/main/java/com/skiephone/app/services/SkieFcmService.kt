package com.skiephone.app.services

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.google.firebase.database.FirebaseDatabase

class SkieFcmService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "New FCM token: $token")
        saveTokenToDatabase(token)
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        Log.d(TAG, "FCM message received from: ${remoteMessage.from}")

        val command = remoteMessage.data["command"] ?: return
        val deviceId = remoteMessage.data["deviceId"] ?: return

        Log.d(TAG, "Command: $command for device: $deviceId")

        handleCommand(command)
    }

    private fun handleCommand(command: String) {
        when (command) {
            "LOCK_DEVICE" -> {
                Log.d(TAG, "Executing: LOCK_DEVICE")
            }
            "DISABLE_CAMERA" -> {
                Log.d(TAG, "Executing: DISABLE_CAMERA")
            }
            "ENABLE_SCHOOL_MODE" -> {
                Log.d(TAG, "Executing: ENABLE_SCHOOL_MODE")
            }
            "DISABLE_SCHOOL_MODE" -> {
                Log.d(TAG, "Executing: DISABLE_SCHOOL_MODE")
            }
            "SYNC_POLICY" -> {
                Log.d(TAG, "Executing: SYNC_POLICY")
            }
            else -> Log.d(TAG, "Unknown command: $command")
        }
    }

    private fun saveTokenToDatabase(token: String) {
        val deviceId = android.provider.Settings.Secure.getString(
            contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        )
        FirebaseDatabase.getInstance().reference
            .child("devices")
            .child(deviceId)
            .child("fcmToken")
            .setValue(token)
            .addOnSuccessListener { Log.d(TAG, "FCM token saved to RTDB") }
            .addOnFailureListener { Log.e(TAG, "Failed to save FCM token", it) }
    }

    companion object {
        private const val TAG = "SkieFcmService"
    }
}