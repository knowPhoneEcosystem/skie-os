package com.skiephone.app.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.provider.Settings
import android.util.Log
import com.google.firebase.database.FirebaseDatabase

class SkieBatteryManager(private val context: Context) {

    private val deviceId = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ANDROID_ID
    )

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level >= 0 && scale > 0) {
                val pct = (level * 100 / scale)
                FirebaseDatabase.getInstance()
                    .getReference("devices/$deviceId/battery")
                    .setValue(pct)
                Log.d(TAG, "Battery: $pct%")
            }
        }
    }

    fun start() {
        context.registerReceiver(
            batteryReceiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        Log.d(TAG, "Battery manager started")
    }

    fun stop() {
        try {
            context.unregisterReceiver(batteryReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Battery receiver already unregistered")
        }
    }

    companion object {
        private const val TAG = "SkieBatteryManager"
    }
}