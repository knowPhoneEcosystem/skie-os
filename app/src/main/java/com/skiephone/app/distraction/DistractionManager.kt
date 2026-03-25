package com.skiephone.app.distraction

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.work.*
import com.google.firebase.database.FirebaseDatabase
import com.skiephone.app.admin.SkieDeviceAdminReceiver
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * DistractionManager — source of truth for all distraction timer operations.
 *
 * Sync order (always):
 *   1. Read RTDB distractionTimers node
 *   2. Update SharedPrefs
 *   3. Apply: schedule WorkManager tasks + suspend/unsuspend via DPM
 *
 * SharedPrefs key: "distraction_timers"
 * Format: JSON object { "com.google.android.youtube": { expiresAt, durationMs, enabledAt, active } }
 */
object DistractionManager {

    private const val TAG = "DistractionManager"
    private const val PREFS_NAME = "skie_distraction"
    private const val PREFS_KEY_TIMERS = "distraction_timers"
    private const val PREFS_KEY_DEFAULT_DURATION = "default_duration_ms"
    private const val DEFAULT_DURATION_MS = 10 * 60 * 1000L // 10 minutes

    // ── SharedPrefs helpers ────────────────────────────────────────────────────

    fun getDefaultDurationMs(context: Context): Long {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(PREFS_KEY_DEFAULT_DURATION, DEFAULT_DURATION_MS)
    }

    fun setDefaultDurationMs(context: Context, durationMs: Long) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putLong(PREFS_KEY_DEFAULT_DURATION, durationMs).apply()
        // Sync to RTDB
        val deviceId = getDeviceId(context)
        FirebaseDatabase.getInstance()
            .getReference("devices/$deviceId/distractionDefaultDurationMs")
            .setValue(durationMs)
    }

    private fun readTimersFromPrefs(context: Context): JSONObject {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREFS_KEY_TIMERS, "{}") ?: "{}"
        return try { JSONObject(raw) } catch (e: Exception) { JSONObject() }
    }

    private fun writeTimersToPrefs(context: Context, timers: JSONObject) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(PREFS_KEY_TIMERS, timers.toString()).apply()
    }

    private fun getDeviceId(context: Context): String =
        android.provider.Settings.Secure.getString(
            context.contentResolver, android.provider.Settings.Secure.ANDROID_ID
        )

    // ── Core: sync RTDB → SharedPrefs → apply ────────────────────────────────

    /**
     * Full sync. Called on:
     *   - Service start
     *   - Boot
     *   - Connectivity restored
     *   - After any timer set/cancel
     */
    fun syncAndApply(context: Context) {
        val deviceId = getDeviceId(context)
        Log.d(TAG, "syncAndApply: fetching RTDB distractionTimers")

        FirebaseDatabase.getInstance()
            .getReference("devices/$deviceId/distractionTimers")
            .get()
            .addOnSuccessListener { snap ->
                val rtdbTimers = JSONObject()
                if (snap.exists()) {
                    snap.children.forEach { child ->
                        try {
                            val pkg = child.key?.replace("_", ".") ?: return@forEach
                            val obj = JSONObject()
                            obj.put("expiresAt", child.child("expiresAt").getValue(Long::class.java) ?: 0L)
                            obj.put("durationMs", child.child("durationMs").getValue(Long::class.java) ?: 0L)
                            obj.put("enabledAt", child.child("enabledAt").getValue(Long::class.java) ?: 0L)
                            obj.put("active", child.child("active").getValue(Boolean::class.java) ?: false)
                            rtdbTimers.put(pkg, obj)
                        } catch (e: Exception) {
                            Log.e(TAG, "syncAndApply: parse error ${e.message}")
                        }
                    }
                }
                // Write to SharedPrefs
                writeTimersToPrefs(context, rtdbTimers)
                Log.d(TAG, "syncAndApply: wrote ${rtdbTimers.length()} timers to SharedPrefs")
                // Apply
                applyFromPrefs(context)
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "syncAndApply: RTDB fetch failed — applying from SharedPrefs cache: ${e.message}")
                // Fallback to cached SharedPrefs
                applyFromPrefs(context)
            }

        // Also sync default duration from RTDB
        FirebaseDatabase.getInstance()
            .getReference("devices/$deviceId/distractionDefaultDurationMs")
            .get()
            .addOnSuccessListener { snap ->
                if (snap.exists()) {
                    val ms = snap.getValue(Long::class.java) ?: DEFAULT_DURATION_MS
                    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit().putLong(PREFS_KEY_DEFAULT_DURATION, ms).apply()
                }
            }
    }

    /**
     * Read SharedPrefs and apply: schedule workers for active timers,
     * suspend apps whose timers have already expired.
     */
    fun applyFromPrefs(context: Context) {
        val now = System.currentTimeMillis()
        val timers = readTimersFromPrefs(context)
        val keys = timers.keys()
        while (keys.hasNext()) {
            val pkg = keys.next()
            try {
                val obj = timers.getJSONObject(pkg)
                val active = obj.optBoolean("active", false)
                val expiresAt = obj.optLong("expiresAt", 0L)

                if (!active) {
                    // Ensure app is suspended
                    suspendApp(context, pkg)
                    continue
                }

                if (expiresAt <= now) {
                    // Already expired — suspend and mark inactive
                    Log.d(TAG, "applyFromPrefs: $pkg timer expired — suspending")
                    onTimerExpired(context, pkg)
                } else {
                    // Still active — ensure unsuspended and schedule worker
                    unsuspendApp(context, pkg)
                    scheduleWorker(context, pkg, expiresAt)
                    Log.d(TAG, "applyFromPrefs: $pkg active, ${(expiresAt - now) / 1000}s remaining")
                }
            } catch (e: Exception) {
                Log.e(TAG, "applyFromPrefs: $pkg error — ${e.message}")
            }
        }
    }

    // ── Enable distraction timer ───────────────────────────────────────────────

    fun enableTimer(context: Context, packageName: String, durationMs: Long) {
        val now = System.currentTimeMillis()
        val expiresAt = now + durationMs
        val deviceId = getDeviceId(context)
        val rtdbKey = packageName.replace(".", "_")

        Log.d(TAG, "enableTimer: $packageName for ${durationMs / 1000}s, expires at $expiresAt")

        // 1. Write to RTDB first
        val timerData = mapOf(
            "expiresAt" to expiresAt,
            "durationMs" to durationMs,
            "enabledAt" to now,
            "active" to true
        )
        FirebaseDatabase.getInstance()
            .getReference("devices/$deviceId/distractionTimers/$rtdbKey")
            .setValue(timerData)
            .addOnSuccessListener {
                Log.d(TAG, "enableTimer: RTDB write success for $packageName")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "enableTimer: RTDB write failed — ${e.message}")
            }

        // 2. Update SharedPrefs
        val timers = readTimersFromPrefs(context)
        val obj = JSONObject()
        obj.put("expiresAt", expiresAt)
        obj.put("durationMs", durationMs)
        obj.put("enabledAt", now)
        obj.put("active", true)
        timers.put(packageName, obj)
        writeTimersToPrefs(context, timers)

        // 3. Unsuspend app + update effectiveApps
        unsuspendApp(context, packageName)
        updateEffectiveAppsAllowed(context, packageName, allowed = true, expiresAt = expiresAt)

        // 4. Schedule WorkManager worker
        scheduleWorker(context, packageName, expiresAt)
    }

    // ── Cancel distraction timer ───────────────────────────────────────────────

    fun cancelTimer(context: Context, packageName: String) {
        val deviceId = getDeviceId(context)
        val rtdbKey = packageName.replace(".", "_")

        Log.d(TAG, "cancelTimer: $packageName")

        // 1. Update RTDB
        FirebaseDatabase.getInstance()
            .getReference("devices/$deviceId/distractionTimers/$rtdbKey")
            .updateChildren(mapOf("active" to false))
            .addOnFailureListener { e -> Log.e(TAG, "cancelTimer: RTDB failed — ${e.message}") }

        // 2. Update SharedPrefs
        val timers = readTimersFromPrefs(context)
        if (timers.has(packageName)) {
            val obj = timers.getJSONObject(packageName)
            obj.put("active", false)
            timers.put(packageName, obj)
            writeTimersToPrefs(context, timers)
        }

        // 3. Cancel WorkManager worker
        cancelWorker(context, packageName)

        // 4. Suspend app + update effectiveApps
        suspendApp(context, packageName)
        updateEffectiveAppsAllowed(context, packageName, allowed = false, expiresAt = null)
    }

    // ── Called when WorkManager fires (timer expired) ─────────────────────────

    fun onTimerExpired(context: Context, packageName: String) {
        val deviceId = getDeviceId(context)
        val rtdbKey = packageName.replace(".", "_")

        Log.d(TAG, "onTimerExpired: $packageName")

        // 1. Mark inactive in RTDB (queued if offline — fires on reconnect)
        FirebaseDatabase.getInstance()
            .getReference("devices/$deviceId/distractionTimers/$rtdbKey")
            .updateChildren(mapOf("active" to false))

        // 2. Update SharedPrefs
        val timers = readTimersFromPrefs(context)
        if (timers.has(packageName)) {
            timers.getJSONObject(packageName).put("active", false)
            writeTimersToPrefs(context, timers)
        }

        // 3. Suspend app
        suspendApp(context, packageName)

        // 4. Update effectiveApps allowed=false, clear expiresAt
        updateEffectiveAppsAllowed(context, packageName, allowed = false, expiresAt = null)
    }

    // ── DPM: suspend / unsuspend ──────────────────────────────────────────────

    private fun getDpm(context: Context): DevicePolicyManager =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    private fun getAdmin(context: Context): ComponentName =
        ComponentName(context, SkieDeviceAdminReceiver::class.java)

    fun suspendApp(context: Context, packageName: String) {
        try {
            val dpm = getDpm(context)
            if (dpm.isDeviceOwnerApp(context.packageName)) {
                dpm.setPackagesSuspended(getAdmin(context), arrayOf(packageName), true)
                Log.d(TAG, "suspendApp: $packageName suspended")
            }
        } catch (e: Exception) {
            Log.e(TAG, "suspendApp: $packageName — ${e.message}")
        }
    }

    fun unsuspendApp(context: Context, packageName: String) {
        try {
            val dpm = getDpm(context)
            if (dpm.isDeviceOwnerApp(context.packageName)) {
                dpm.setPackagesSuspended(getAdmin(context), arrayOf(packageName), false)
                Log.d(TAG, "unsuspendApp: $packageName unsuspended")
            }
        } catch (e: Exception) {
            Log.e(TAG, "unsuspendApp: $packageName — ${e.message}")
        }
    }

    // ── RTDB: update effectiveApps ────────────────────────────────────────────

    private fun updateEffectiveAppsAllowed(
        context: Context,
        packageName: String,
        allowed: Boolean,
        expiresAt: Long?
    ) {
        val deviceId = getDeviceId(context)
        val ref = FirebaseDatabase.getInstance()
            .getReference("devices/$deviceId/effectiveApps")

        ref.get().addOnSuccessListener { snap ->
            if (!snap.exists()) return@addOnSuccessListener
            val updated = mutableListOf<Map<String, Any?>>()
            var found = false
            snap.children.forEach { child ->
                val pkg = child.child("packageName").getValue(String::class.java)
                if (pkg == packageName) {
                    found = true
                    val entry = mutableMapOf<String, Any?>()
                    child.children.forEach { f -> entry[f.key!!] = f.value }
                    entry["allowed"] = allowed
                    entry["status"] = if (allowed) "installed" else "suspended"
                    if (expiresAt != null) entry["distractionExpiresAt"] = expiresAt
                    else entry.remove("distractionExpiresAt")
                    updated.add(entry)
                } else {
                    val entry = mutableMapOf<String, Any?>()
                    child.children.forEach { f -> entry[f.key!!] = f.value }
                    updated.add(entry)
                }
            }
            if (found) {
                ref.setValue(updated)
                    .addOnSuccessListener { Log.d(TAG, "updateEffectiveApps: $packageName allowed=$allowed") }
                    .addOnFailureListener { e -> Log.e(TAG, "updateEffectiveApps: write failed — ${e.message}") }
            }
        }.addOnFailureListener { e ->
            Log.e(TAG, "updateEffectiveAppsAllowed: read failed — ${e.message}")
        }
    }

    // ── WorkManager scheduling ────────────────────────────────────────────────

    private fun workerTag(packageName: String) = "distraction_$packageName"

    private fun scheduleWorker(context: Context, packageName: String, expiresAt: Long) {
        val delayMs = (expiresAt - System.currentTimeMillis()).coerceAtLeast(0L)

        // Cancel any existing worker for this package first
        cancelWorker(context, packageName)

        val data = Data.Builder()
            .putString("packageName", packageName)
            .putLong("expiresAt", expiresAt)
            .build()

        val request = OneTimeWorkRequestBuilder<DistractionTimerWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(data)
            .addTag(workerTag(packageName))
            .setBackoffCriteria(BackoffPolicy.LINEAR, 1, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context).enqueue(request)
        Log.d(TAG, "scheduleWorker: $packageName in ${delayMs / 1000}s")
    }

    private fun cancelWorker(context: Context, packageName: String) {
        WorkManager.getInstance(context).cancelAllWorkByTag(workerTag(packageName))
        Log.d(TAG, "cancelWorker: $packageName cancelled")
    }
}