package com.skiephone.app.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
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
import com.skiephone.app.admin.SkieDeviceAdminReceiver

class SkieLocationService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // CRITICAL ORDERING — two OS requirements are in tension here and both
        // must be satisfied:
        //  1. startForeground() must be called within seconds of this service
        //     being started via startForegroundService(), with NO exceptions —
        //     skipping it (even to "fail safely") kills the whole app process
        //     with ForegroundServiceDidNotStartInTimeException.
        //  2. startForeground() with foregroundServiceType="location" itself
        //     throws SecurityException if the location runtime permission isn't
        //     actually granted at that exact moment.
        //
        // Resolution: the permission self-heal (DPM setPermissionGrantState) is a
        // synchronous, fast Binder call — not callback-based — so it can run
        // first with negligible delay, then startForeground() is called
        // immediately after, satisfying requirement 1 in all cases while giving
        // requirement 2 the best chance of succeeding.
        if (!hasLocationPermission()) {
            Log.w(TAG, "onStartCommand: location permission not granted — attempting self-heal via DPM")
            try { trySelfHealPermission() } catch (e: Exception) {
                Log.e(TAG, "onStartCommand: self-heal threw — ${e.message}")
            }
        }

        try {
            startForegroundCompat()
        } catch (e: Exception) {
            // Permission still missing (self-heal failed or not device owner) —
            // startForeground() itself threw. The OS contract for THIS start
            // command can no longer be satisfied; stop immediately. This is a
            // narrow, unavoidable case (e.g. device owner state itself broken)
            // rather than the previous bug, which skipped startForeground()
            // unconditionally on any missing permission.
            Log.e(TAG, "onStartCommand: startForegroundCompat failed — ${e.message}")
            try { stopSelf() } catch (e2: Exception) { /* already stopping */ }
            return START_NOT_STICKY
        }

        try {
            reportLocation()
        } catch (e: Exception) {
            // Defense in depth — no exception from this service should ever be
            // allowed to crash the app process.
            Log.e(TAG, "onStartCommand: unexpected error — ${e.message}")
            try { stopSelf() } catch (e2: Exception) { /* already stopping */ }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    /**
     * Self-heal: if this app is device owner, silently grant the location
     * permissions to itself via DPM — no user dialog needed. This guards
     * against any case where the upstream grant (SkiePolicyEngine.start())
     * hasn't run yet or didn't take effect for this process.
     */
    private fun trySelfHealPermission() {
        try {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = ComponentName(this, SkieDeviceAdminReceiver::class.java)
            if (!dpm.isDeviceOwnerApp(packageName)) {
                Log.w(TAG, "trySelfHealPermission: not device owner — cannot self-grant")
                return
            }
            listOf(
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION
            ).forEach { perm ->
                try {
                    dpm.setPermissionGrantState(admin, packageName, perm, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
                } catch (e: Exception) {
                    Log.e(TAG, "trySelfHealPermission: grant $perm failed — ${e.message}")
                }
            }
            Log.d(TAG, "trySelfHealPermission: self-heal grant attempted")
        } catch (e: Exception) {
            Log.e(TAG, "trySelfHealPermission: ${e.message}")
        }
    }

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
        if (!hasLocationPermission()) {
            Log.e(TAG, "reportLocation: permission not granted")
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