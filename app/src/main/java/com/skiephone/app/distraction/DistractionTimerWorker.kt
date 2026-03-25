package com.skiephone.app.distraction

import android.content.Context
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters

/**
 * DistractionTimerWorker — fired by WorkManager when distraction timer expires.
 *
 * WorkManager guarantees execution even after:
 *   - App kill
 *   - Device reboot (WorkManager persists work across reboots)
 *   - Battery optimization
 *
 * On execution: delegates to DistractionManager.onTimerExpired()
 * which suspends the app, updates SharedPrefs and RTDB.
 */
class DistractionTimerWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : Worker(context, workerParams) {

    companion object {
        private const val TAG = "DistractionTimerWorker"
    }

    override fun doWork(): Result {
        val packageName = inputData.getString("packageName") ?: run {
            Log.e(TAG, "doWork: no packageName in input data")
            return Result.failure()
        }
        val expiresAt = inputData.getLong("expiresAt", 0L)
        val now = System.currentTimeMillis()

        Log.d(TAG, "doWork: $packageName expiresAt=$expiresAt now=$now")

        // Double-check: re-read SharedPrefs in case timer was cancelled
        // while worker was queued
        try {
            val prefs = context.getSharedPreferences("skie_distraction", Context.MODE_PRIVATE)
            val raw = prefs.getString("distraction_timers", "{}") ?: "{}"
            val timers = org.json.JSONObject(raw)

            if (!timers.has(packageName)) {
                Log.d(TAG, "doWork: $packageName not in SharedPrefs — skipping (was cancelled)")
                return Result.success()
            }

            val timerObj = timers.getJSONObject(packageName)
            val active = timerObj.optBoolean("active", false)
            val savedExpiresAt = timerObj.optLong("expiresAt", 0L)

            if (!active) {
                Log.d(TAG, "doWork: $packageName already inactive — skipping")
                return Result.success()
            }

            // If a new timer was set (expiresAt changed), this worker is stale
            if (savedExpiresAt != expiresAt && savedExpiresAt > now) {
                Log.d(TAG, "doWork: $packageName has newer timer — skipping stale worker")
                return Result.success()
            }

        } catch (e: Exception) {
            Log.e(TAG, "doWork: SharedPrefs check failed — ${e.message}")
        }

        // Fire expiry
        DistractionManager.onTimerExpired(context, packageName)
        return Result.success()
    }
}