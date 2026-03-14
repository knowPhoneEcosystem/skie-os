package com.skiephone.app.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.firebase.database.FirebaseDatabase

class SkieLocationService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        reportLocation()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // Required on Android 14+ for foreground services with location type
    private fun startForegroundCompat() {
        val channelId = "skie_location"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "Skie Location", NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false); setSound(null, null) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Skie")
            .setContentText("Fetching location…")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build()
        startForeground(1002, notification)
    }

    private fun reportLocation() {
        if (ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "Location permission not granted")
            stopSelf()
            return
        }

        LocationServices.getFusedLocationProviderClient(this).lastLocation
            .addOnSuccessListener { location ->
                if (location != null) {
                    val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
                    FirebaseDatabase.getInstance().reference
                        .child("devices").child(deviceId).child("location")
                        .setValue(mapOf(
                            "latitude"  to location.latitude,
                            "longitude" to location.longitude,
                            "timestamp" to System.currentTimeMillis()
                        ))
                        .addOnSuccessListener { Log.d(TAG, "location saved: ${location.latitude}, ${location.longitude}") }
                        .addOnFailureListener { Log.e(TAG, "location save failed: ${it.message}") }
                } else {
                    Log.w(TAG, "lastLocation returned null — no cached fix available")
                }
                stopSelf()
            }
            .addOnFailureListener {
                Log.e(TAG, "getFusedLocation failed: ${it.message}")
                stopSelf()
            }
    }

    companion object {
        private const val TAG = "SkieLocationService"
    }
}