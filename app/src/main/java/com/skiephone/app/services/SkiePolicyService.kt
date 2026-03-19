package com.skiephone.app.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.skiephone.app.policy.SkieCommandManager
import com.skiephone.app.policy.SkiePolicyEngine
import com.skiephone.app.policy.SkieUpdateManager

class SkiePolicyService : Service() {

    private lateinit var policyEngine: SkiePolicyEngine
    private lateinit var batteryManager: SkieBatteryManager
    private lateinit var installedAppsManager: SkieInstalledAppsManager
    private lateinit var locationManager: SkieLocationManager
    private lateinit var travelManager: SkieTravelManager
    private lateinit var sosManager: SkieSOSManager
    private var deviceId: String = ""

    override fun onCreate() {
        super.onCreate()

        // Global crash guard — restart service if uncaught exception occurs
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            android.util.Log.e("SkiePolicyService", "UNCAUGHT EXCEPTION on ${thread.name}: ${throwable.message}", throwable)
            // Restart the service after crash
            val restartIntent = android.content.Intent(applicationContext, SkiePolicyService::class.java)
            val pendingIntent = android.app.PendingIntent.getService(
                applicationContext, 2, restartIntent,
                android.app.PendingIntent.FLAG_ONE_SHOT or android.app.PendingIntent.FLAG_MUTABLE
            )
            val alarmManager = getSystemService(ALARM_SERVICE) as android.app.AlarmManager
            alarmManager.set(
                android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                android.os.SystemClock.elapsedRealtime() + 2000,
                pendingIntent
            )
        }

        startForeground()

        deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)

        try { writeImeiToRtdb() } catch (e: Exception) { android.util.Log.e("SkiePolicyService", "writeImeiToRtdb: ${e.message}") }

        try { writeBaselineAppsIfNeeded() } catch (e: Exception) { android.util.Log.e("SkiePolicyService", "writeBaselineAppsIfNeeded: ${e.message}") }

        // Check if Play Store tunnel was open when device lost connectivity — close if expired
        com.skiephone.app.admin.PlayStoreTunnelReceiver.checkAndCloseStaleTunnel(this)

        policyEngine = SkiePolicyEngine(this)
        policyEngine.start()

        SkieUpdateManager(this).startWatching()
        SkieCommandManager(this).startListening()

        batteryManager = SkieBatteryManager(this)
        batteryManager.start()

        installedAppsManager = SkieInstalledAppsManager(this)
        installedAppsManager.startListening()
        installedAppsManager.sync()

        locationManager = SkieLocationManager(this, deviceId)
        locationManager.startListening()

        travelManager = SkieTravelManager(this, deviceId)
        travelManager.startListening()

        sosManager = SkieSOSManager(this, deviceId, travelManager)
        sosManager.startListening()

        setupOnlinePresence()

        Log.d(TAG, "Policy service started — deviceId: $deviceId")
    }

    /**
     * Read IMEI1 from slot 0 and write to RTDB.
     * As device owner, READ_PHONE_STATE is granted silently.
     * Always uses slot 0 (IMEI1) — works for single SIM and dual SIM.
     * Falls back gracefully if IMEI unavailable.
     */
    private fun writeImeiToRtdb() {
        try {
            val tm = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
            val imei = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                tm.getImei(0)
            } else {
                @Suppress("DEPRECATION")
                tm.deviceId
            }
            if (!imei.isNullOrBlank()) {
                FirebaseDatabase.getInstance()
                    .getReference("devices/$deviceId/imei")
                    .setValue(imei)
                Log.d(TAG, "IMEI written: $imei")
            } else {
                Log.w(TAG, "IMEI unavailable — skipping write")
            }
        } catch (e: Exception) {
            Log.e(TAG, "writeImeiToRtdb failed: ${e.message}")
        }
    }

    private fun setupOnlinePresence() {
        val db = FirebaseDatabase.getInstance()
        val onlineRef = db.getReference("devices/$deviceId/online")
        val lastSeenRef = db.getReference("devices/$deviceId/lastSeen")

        val connectedRef = db.getReference(".info/connected")
        connectedRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val connected = snapshot.getValue(Boolean::class.java) ?: false
                if (connected) {
                    onlineRef.onDisconnect().setValue(false)
                    lastSeenRef.onDisconnect().setValue(com.google.firebase.database.ServerValue.TIMESTAMP)
                    onlineRef.setValue(true)
                    lastSeenRef.setValue(com.google.firebase.database.ServerValue.TIMESTAMP)
                    Log.d(TAG, "Firebase connected — online: true")
                } else {
                    Log.d(TAG, "Firebase disconnected")
                }
            }
            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Connected listener cancelled: ${error.message}")
            }
        })
    }

    private fun startForeground() {
        val channelId = "skie_policy_service"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "Skie Device Management", NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
                setSound(null, null)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Skie")
            .setContentText("Device is managed")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build()
        try {
            startForeground(1001, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground: ${e.message}")
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        batteryManager.stop()
        installedAppsManager.stopListening()
        locationManager.stop()
        travelManager.stop()
        sosManager.stop()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val restartIntent = Intent(applicationContext, SkiePolicyService::class.java)
        val pendingIntent = android.app.PendingIntent.getService(
            applicationContext, 1, restartIntent,
            android.app.PendingIntent.FLAG_ONE_SHOT or android.app.PendingIntent.FLAG_MUTABLE
        )
        val alarmManager = getSystemService(ALARM_SERVICE) as android.app.AlarmManager
        alarmManager.set(
            android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
            android.os.SystemClock.elapsedRealtime() + 1000,
            pendingIntent
        )
        super.onTaskRemoved(rootIntent)
    }

    /**
     * Writes baseline apps snapshot on first boot only.
     * Baseline = all apps present at provisioning time (system + OEM bloatware + pre-installed).
     * Used by dashboard to distinguish user-installed apps from pre-installed ones.
     * Never overwrites once written.
     */
    private fun writeBaselineAppsIfNeeded() {
        val baselineRef = com.google.firebase.database.FirebaseDatabase.getInstance()
            .getReference("devices/$deviceId/baselineApps")
        baselineRef.get().addOnSuccessListener { snapshot ->
            if (snapshot.exists()) {
                android.util.Log.d("SkiePolicyService", "baselineApps already exists — skipping")
                return@addOnSuccessListener
            }
            val pm = packageManager
            val allApps = pm.getInstalledApplications(android.content.pm.PackageManager.GET_META_DATA)
                .map { it.packageName }
            baselineRef.setValue(allApps)
            android.util.Log.d("SkiePolicyService", "baselineApps written: \${allApps.size} packages")
        }
    }

    companion object {
        private const val TAG = "SkiePolicyService"
    }
}