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
import com.google.firebase.auth.FirebaseAuth
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
    private lateinit var geofenceManager: SkieGeofenceManager
    private var deviceId: String = ""



    override fun onCreate() {
        super.onCreate()
        isRunning = true

        // Global crash guard — restart service after uncaught exception
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "UNCAUGHT EXCEPTION on ${thread.name}: ${throwable.message}", throwable)
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

        // Temporary: enable Firebase RTDB debug logging to diagnose online presence drops.
        // Remove once root cause is confirmed.
        FirebaseDatabase.getInstance().setLogLevel(com.google.firebase.database.Logger.Level.DEBUG)

        deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)

        // Sign in anonymously so all Firebase reads/writes satisfy security rules.
        //
        // CRITICAL ORDERING — do NOT call setupOnlinePresence() or startFirebaseWork()
        // directly here. writeAnonUidToDeviceClaims() gates both calls inside its own
        // addOnSuccessListener, ensuring anonUid is committed to RTDB server-side
        // BEFORE any listener attaches. Without this gate, all listeners fire before
        // the device's identity is established, get Permission Denied from RTDB rules,
        // and are permanently cancelled (onCancelled never auto-retries on permission errors).
        // This was the root cause of the double-restart-after-reprovision bug.
        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser != null) {
            Log.d(TAG, "Firebase already signed in: ${auth.currentUser?.uid}")
            writeAnonUidToDeviceClaims(auth.currentUser!!.uid)
        } else {
            auth.signInAnonymously()
                .addOnSuccessListener {
                    Log.d(TAG, "Firebase anonymous sign-in OK: ${auth.currentUser?.uid}")
                    writeAnonUidToDeviceClaims(auth.currentUser!!.uid)
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Firebase sign-in FAILED: ${e.message} — retrying in 5s")
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        auth.signInAnonymously()
                            .addOnSuccessListener {
                                Log.d(TAG, "Firebase sign-in retry OK")
                                writeAnonUidToDeviceClaims(auth.currentUser!!.uid)
                            }
                            .addOnFailureListener { e2 ->
                                Log.e(TAG, "Firebase sign-in retry FAILED: ${e2.message} — starting offline")
                                // Both retries failed — start without identity (offline mode).
                                // RTDB offline persistence will queue writes; listeners will
                                // recover on next restart when sign-in succeeds.
                                setupOnlinePresence()
                                startFirebaseWork()
                            }
                    }, 5000)
                }
        }

        Log.d(TAG, "Policy service onCreate done — deviceId: $deviceId")
    }

    /**
     * setupOnlinePresence is intentionally called BEFORE startFirebaseWork.
     * Reason: after a silent OTA install, Android restarts the process immediately.
     * Firebase RTDB reconnects and fires .info/connected within ~200ms of the process
     * starting. If we wait for all listeners to attach first, we miss that event and
     * online status never gets written until the next connectivity change.
     *
     * The .info/connected listener is self-healing — it fires again on every
     * reconnect, so online status will always recover even if the first event is missed.
     */
    private fun setupOnlinePresence() {
        val db = FirebaseDatabase.getInstance()
        val onlineRef   = db.getReference("devices/$deviceId/online")
        val lastSeenRef = db.getReference("devices/$deviceId/lastSeen")

        db.getReference(".info/connected").addValueEventListener(object : ValueEventListener {
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

    /**
     * All remaining Firebase work — called after sign-in and online presence are set up.
     */
    private fun startFirebaseWork() {
        try { writeImeiToRtdb() } catch (e: Exception) { Log.e(TAG, "writeImeiToRtdb: ${e.message}") }
        try { writeAppVersionToRtdb() } catch (e: Exception) { Log.e(TAG, "writeAppVersionToRtdb: ${e.message}") }
        try { writeBaselineAppsIfNeeded() } catch (e: Exception) { Log.e(TAG, "writeBaselineAppsIfNeeded: ${e.message}") }

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

        com.skiephone.app.distraction.DistractionManager.syncAndApply(this)

        locationManager = SkieLocationManager(this, deviceId)
        locationManager.startListening()

        travelManager = SkieTravelManager(this, deviceId)
        travelManager.startListening()

        sosManager = SkieSOSManager(this, deviceId, travelManager)
        sosManager.startListening()

        geofenceManager = SkieGeofenceManager(this, deviceId)
        geofenceManager.start()

        Log.d(TAG, "startFirebaseWork complete — all listeners attached")
    }

    private fun writeAppVersionToRtdb() {
        val pInfo = packageManager.getPackageInfo(packageName, 0)
        val versionName = pInfo.versionName ?: "unknown"
        val versionCode = androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(pInfo).toInt()
        FirebaseDatabase.getInstance()
            .getReference("devices/$deviceId/appVersion")
            .setValue(mapOf("versionName" to versionName, "versionCode" to versionCode))
        Log.d(TAG, "appVersion written: $versionName ($versionCode)")
    }

    private fun writeImeiToRtdb() {
        try {
            val tm = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
            val imei = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                tm.getImei(0)
            } else {
                @Suppress("DEPRECATION") tm.deviceId
            }
            if (!imei.isNullOrBlank()) {
                FirebaseDatabase.getInstance()
                    .getReference("devices/$deviceId/imei")
                    .setValue(imei)
                Log.d(TAG, "IMEI written: $imei")
            }
        } catch (e: SecurityException) {
            // READ_PRIVILEGED_PHONE_STATE is a platform-signed permission —
            // unavailable to Device Owner apps. IMEI write skipped; ANDROID_ID
            // is the canonical device identity throughout the app.
            Log.d(TAG, "IMEI unavailable (privileged permission required) — skipping")
        }
    }

    private fun writeBaselineAppsIfNeeded() {
        val baselineRef = FirebaseDatabase.getInstance()
            .getReference("devices/$deviceId/baselineApps")
        baselineRef.get().addOnSuccessListener { snapshot ->
            if (snapshot.exists()) return@addOnSuccessListener
            val allApps = packageManager
                .getInstalledApplications(android.content.pm.PackageManager.GET_META_DATA)
                .map { it.packageName }
            baselineRef.setValue(allApps)
            Log.d(TAG, "baselineApps written: ${allApps.size} packages")
        }
    }

    /**
     * STEP 1 — Write anonUid to RTDB and gate all Firebase listener startup.
     *
     * This is the critical ordering fix for the double-restart-after-reprovision bug:
     *   - setupOnlinePresence() and startFirebaseWork() are called ONLY inside
     *     addOnSuccessListener — after the server confirms the anonUid write.
     *   - This guarantees device identity is established in RTDB before any listener
     *     attaches, so RTDB rules pass on first connect instead of firing Permission
     *     Denied and permanently cancelling all listeners.
     *
     * STEP 2 (non-blocking, after gate) — write Firestore deviceAuth/{anonUid}
     *   - Reads parentId from RTDB deviceClaims/{deviceId}/parentId
     *   - Cleans up old deviceAuth doc if anonUid changed (re-provision)
     *   - Writes new deviceAuth/{anonUid} → { parentId, deviceId }
     *   - This allows device to read Firestore users/{parentId} for SOS phone etc.
     *   - Non-blocking — does not delay listener startup
     */
    private fun writeAnonUidToDeviceClaims(uid: String) {
        val rtdb = FirebaseDatabase.getInstance()
        val anonUidRef = rtdb.getReference("deviceClaims/$deviceId/anonUid")
        val firestore = com.google.firebase.firestore.FirebaseFirestore.getInstance()

        // STEP 1: Write anonUid — wait for server confirmation before starting listeners.
        // Rule: newData.val() === auth.uid — always passes, allows re-provision overwrite.
        anonUidRef.setValue(uid)
            .addOnSuccessListener {
                Log.d(TAG, "anonUid written to RTDB: $uid — starting Firebase work")
                // Identity established — NOW safe to attach all listeners
                setupOnlinePresence()
                startFirebaseWork()
                // STEP 2: Firestore deviceAuth — non-blocking, runs after listeners start
                writeDeviceAuthAsync(uid, firestore, rtdb)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "anonUid RTDB write failed: ${e.message} — starting Firebase work anyway")
                // Write failed but start listeners — will recover on reconnect/restart
                setupOnlinePresence()
                startFirebaseWork()
            }
    }

    /**
     * Non-blocking: reads parentId from RTDB, cleans up old Firestore deviceAuth doc,
     * writes new deviceAuth/{anonUid} → { parentId, deviceId }.
     * Called after listeners are already running — does not block startup.
     */
    private fun writeDeviceAuthAsync(
        uid: String,
        firestore: com.google.firebase.firestore.FirebaseFirestore,
        rtdb: com.google.firebase.database.FirebaseDatabase
    ) {
        rtdb.getReference("deviceClaims/$deviceId").get()
            .addOnSuccessListener { snap ->
                val oldAnonUid = snap.child("anonUid").getValue(String::class.java)
                val parentId = snap.child("parentId").getValue(String::class.java)

                // Clean up old deviceAuth doc if anonUid changed (re-provision)
                if (oldAnonUid != null && oldAnonUid != uid) {
                    firestore.collection("deviceAuth").document(oldAnonUid)
                        .delete()
                        .addOnSuccessListener { Log.d(TAG, "Old deviceAuth doc deleted: $oldAnonUid") }
                        .addOnFailureListener { e -> Log.e(TAG, "Old deviceAuth delete failed: ${e.message}") }
                }

                // Write new deviceAuth doc so Firestore rules can verify device identity
                if (parentId != null) {
                    firestore.collection("deviceAuth").document(uid)
                        .set(mapOf("parentId" to parentId, "deviceId" to deviceId))
                        .addOnSuccessListener { Log.d(TAG, "deviceAuth written: $uid → parentId=$parentId") }
                        .addOnFailureListener { e -> Log.e(TAG, "deviceAuth write failed: ${e.message}") }
                } else {
                    Log.w(TAG, "parentId not in RTDB — deviceAuth not written (device may not be paired yet)")
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "deviceClaims RTDB read failed for deviceAuth: ${e.message}")
            }
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
        isRunning = false
        // isInitialized guards — startFirebaseWork may not have run yet
        // if sign-in was still pending when service was destroyed
        if (::batteryManager.isInitialized) batteryManager.stop()
        if (::installedAppsManager.isInitialized) installedAppsManager.stopListening()
        if (::locationManager.isInitialized) locationManager.stop()
        if (::travelManager.isInitialized) travelManager.stop()
        if (::sosManager.isInitialized) sosManager.stop()
        if (::geofenceManager.isInitialized) geofenceManager.stop()
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

    companion object {
        private const val TAG = "SkiePolicyService"

        /**
         * Static running flag — set true in onCreate, false in onDestroy.
         * Used by LauncherActivity.ensureServiceRunning() to avoid the deprecated
         * ActivityManager.getRunningServices() which is unreliable on Android 12+.
         */
        @Volatile
        var isRunning: Boolean = false
            private set
    }
}