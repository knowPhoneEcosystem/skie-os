package com.skiephone.app.services

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

/**
 * Listens to devices/{deviceId}/locationRequest in RTDB.
 * When the dashboard writes { requestedAt: <timestamp> }, fires SkieLocationService
 * to capture the current GPS fix and write it back to devices/{deviceId}/location.
 *
 * Works regardless of device online/offline state — the request node is persisted
 * and the listener fires as soon as connectivity is restored.
 */
class SkieLocationManager(
    private val context: Context,
    private val deviceId: String
) {

    private val db = FirebaseDatabase.getInstance()
    private val requestRef = db.getReference("devices/$deviceId/locationRequest")
    private var listener: ValueEventListener? = null

    // Track last fulfilled requestedAt to avoid re-triggering on reconnect
    @Volatile private var lastFulfilledAt: Long = -1L

    fun startListening() {
        requestRef.keepSynced(true)

        listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) return

                val requestedAt = snapshot.child("requestedAt").getValue(Long::class.java) ?: 0L
                if (requestedAt == 0L || requestedAt == lastFulfilledAt) {
                    Log.d(TAG, "locationRequest: skipping (already fulfilled or zero)")
                    return
                }

                Log.d(TAG, "locationRequest received at $requestedAt — starting SkieLocationService")
                lastFulfilledAt = requestedAt

                val intent = Intent(context, SkieLocationService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "locationRequest listener cancelled: ${error.message}")
            }
        }

        requestRef.addValueEventListener(listener!!)
        Log.d(TAG, "startListening: watching locationRequest for deviceId=$deviceId")
    }

    fun stop() {
        listener?.let { requestRef.removeEventListener(it) }
        listener = null
        Log.d(TAG, "stop: listener removed")
    }

    companion object {
        private const val TAG = "SkieLocationManager"
    }
}