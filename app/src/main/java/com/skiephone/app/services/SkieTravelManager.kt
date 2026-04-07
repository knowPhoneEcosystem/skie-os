package com.skiephone.app.services

import android.content.Context
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import com.google.firebase.database.*
import kotlin.math.*

/**
 * Manages travel mode location updates and auto-expiry.
 *
 * Changes from original:
 * - Reads endsAt from travelMode node and schedules auto-disable via Handler
 * - cancelAutoExpiry() called when travel is turned off early
 * - No polling — single postDelayed, zero extra RTDB writes
 */
class SkieTravelManager(
    private val context: Context,
    private val deviceId: String
) {

    private val db = FirebaseDatabase.getInstance()
    private val travelRef = db.getReference("devices/$deviceId/travelMode")
    private var travelListener: ValueEventListener? = null

    private val fusedClient = LocationServices.getFusedLocationProviderClient(context)
    private var locationCallback: LocationCallback? = null
    private var travelActive = false

    @Volatile private var destLat: Double = 0.0
    @Volatile private var destLng: Double = 0.0

    private val TRAVEL_INTERVAL_MS = 10_000L
    private val ARRIVAL_RADIUS_METERS = 200.0

    private val handler = Handler(Looper.getMainLooper())
    private var autoExpireRunnable: Runnable? = null

    fun startListening() {
        travelListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val active = snapshot.child("active").getValue(Boolean::class.java) ?: false
                val endsAt = snapshot.child("endsAt").getValue(Long::class.java) ?: 0L
                destLat = snapshot.child("destinationLat").getValue(Double::class.java) ?: 0.0
                destLng = snapshot.child("destinationLng").getValue(Double::class.java) ?: 0.0

                when {
                    active && !travelActive -> {
                        Log.d(TAG, "Travel mode activated, endsAt=$endsAt")
                        travelActive = true
                        startLocationUpdates()
                        if (endsAt > 0L) scheduleAutoExpiry(endsAt)
                    }
                    active && travelActive -> {
                        // Already active — update endsAt if it changed (e.g. parent set it
                        // after child triggered travel with no destination)
                        if (endsAt > 0L) scheduleAutoExpiry(endsAt)
                    }
                    !active && travelActive -> {
                        Log.d(TAG, "Travel mode deactivated")
                        travelActive = false
                        cancelAutoExpiry()
                        stopLocationUpdates()
                    }
                }
            }
            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "travelMode listener cancelled: ${error.message}")
            }
        }
        travelRef.addValueEventListener(travelListener!!)
        Log.d(TAG, "startListening for deviceId=$deviceId")
    }

    // ── Auto-expiry ───────────────────────────────────────────────────────────

    private fun scheduleAutoExpiry(endsAt: Long) {
        cancelAutoExpiry()
        val delay = endsAt - System.currentTimeMillis()
        if (delay <= 0L) { disableTravelMode(); return }
        autoExpireRunnable = Runnable {
            Log.d(TAG, "Travel mode auto-expired at endsAt=$endsAt")
            disableTravelMode()
        }
        handler.postDelayed(autoExpireRunnable!!, delay)
        Log.d(TAG, "Travel auto-expiry scheduled in ${delay / 1000}s")
    }

    private fun cancelAutoExpiry() {
        autoExpireRunnable?.let { handler.removeCallbacks(it) }
        autoExpireRunnable = null
    }

    private fun disableTravelMode() {
        travelRef.updateChildren(mapOf(
            "active"      to false,
            "disabledAt"  to System.currentTimeMillis()
        ))
    }

    // ── Location updates ──────────────────────────────────────────────────────

    private fun startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.ACCESS_FINE_LOCATION
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) { Log.e(TAG, "Location permission not granted"); return }

        val request = LocationRequest.Builder(TRAVEL_INTERVAL_MS)
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setMinUpdateIntervalMillis(8_000L)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                writeLocation(loc)
                checkArrival(loc)
            }
        }

        fusedClient.requestLocationUpdates(request, locationCallback!!, Looper.getMainLooper())
        Log.d(TAG, "Travel location updates started (10s interval)")
    }

    fun startSOSLocationUpdates() {
        if (ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.ACCESS_FINE_LOCATION
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) { Log.e(TAG, "Location permission not granted"); return }

        stopLocationUpdates()

        val request = LocationRequest.Builder(5_000L)
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setMinUpdateIntervalMillis(4_000L)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                writeLocation(loc)
            }
        }

        fusedClient.requestLocationUpdates(request, locationCallback!!, Looper.getMainLooper())
        Log.d(TAG, "SOS high-frequency location started (5s interval)")
    }

    fun stopSOSLocationUpdates() {
        stopLocationUpdates()
        Log.d(TAG, "SOS location updates stopped")
        if (travelActive) startLocationUpdates()
    }

    private fun stopLocationUpdates() {
        locationCallback?.let { fusedClient.removeLocationUpdates(it) }
        locationCallback = null
    }

    private fun writeLocation(loc: Location) {
        db.getReference("devices/$deviceId/location").setValue(
            mapOf(
                "latitude"  to loc.latitude,
                "longitude" to loc.longitude,
                "timestamp" to System.currentTimeMillis(),
                "accuracy"  to loc.accuracy.toDouble()
            )
        )
    }

    private fun checkArrival(loc: Location) {
        if (destLat == 0.0 && destLng == 0.0) return
        val dist = haversineMeters(loc.latitude, loc.longitude, destLat, destLng)
        if (dist <= ARRIVAL_RADIUS_METERS) {
            Log.d(TAG, "Arrived at destination — disabling travel mode")
            travelRef.child("active").setValue(false)
            travelRef.child("arrivedAt").setValue(System.currentTimeMillis())
            travelActive = false
            cancelAutoExpiry()
            stopLocationUpdates()
        }
    }

    private fun haversineMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLng / 2).pow(2)
        return R * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    fun stop() {
        travelListener?.let { travelRef.removeEventListener(it) }
        travelListener = null
        cancelAutoExpiry()
        stopLocationUpdates()
        travelActive = false
    }

    companion object { private const val TAG = "SkieTravelManager" }
}