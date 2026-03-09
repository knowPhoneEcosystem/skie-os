package com.skiephone.app.services

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.firebase.database.FirebaseDatabase
import android.provider.Settings

class SkieLocationService : Service() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        reportLocation()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun reportLocation() {
        if (ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "Location permission not granted")
            stopSelf()
            return
        }

        fusedLocationClient.lastLocation
            .addOnSuccessListener { location ->
                if (location != null) {
                    saveLocationToDatabase(location.latitude, location.longitude)
                } else {
                    Log.d(TAG, "Location is null")
                }
                stopSelf()
            }
            .addOnFailureListener {
                Log.e(TAG, "Failed to get location", it)
                stopSelf()
            }
    }

    private fun saveLocationToDatabase(latitude: Double, longitude: Double) {
        val deviceId = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ANDROID_ID
        )

        val locationData = mapOf(
            "latitude" to latitude,
            "longitude" to longitude,
            "timestamp" to System.currentTimeMillis()
        )

        FirebaseDatabase.getInstance().reference
            .child("devices")
            .child(deviceId)
            .child("location")
            .setValue(locationData)
            .addOnSuccessListener { Log.d(TAG, "Location saved to RTDB") }
            .addOnFailureListener { Log.e(TAG, "Failed to save location", it) }
    }

    companion object {
        private const val TAG = "SkieLocationService"
    }
}