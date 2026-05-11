package com.skiephone.app.services

import android.content.Context
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import com.google.firebase.database.*
import kotlin.math.*

/**
 * SkieGeofenceManager
 *
 * Reads everything it needs from RTDB — no Firestore dependency.
 * The dashboard writes the full geofenceConfig node to RTDB when a zone is activated:
 *
 *   devices/{deviceId}/geofenceConfig/
 *     enabled: true
 *     activeGeofenceId: "abc123"
 *     geofenceName: "Home"
 *     polygon: [{lat, lng}, ...]
 *
 * When the node is null or enabled=false → stop all location polling.
 * When the node is present and enabled=true → start 30s location polling,
 * run point-in-polygon check on every fix, write breach alert on EXIT.
 *
 * Boot reliability: attachConfigListener() is called from start(), which is
 * called from startFirebaseWork() immediately after anonymous sign-in.
 * RTDB offline persistence delivers the last-known config even before network
 * is available, so geofence state is always restored on boot without waiting
 * for any additional conditions.
 */
class SkieGeofenceManager(
    private val context: Context,
    private val deviceId: String
) {
    private val db = FirebaseDatabase.getInstance()
    private val fusedClient = LocationServices.getFusedLocationProviderClient(context)

    private val configRef = db.getReference("devices/$deviceId/geofenceConfig")
    private val alertsRef = db.getReference("devices/$deviceId/geofenceAlerts")
    private val statusRef = db.getReference("devices/$deviceId/geofenceStatus")

    private var configListener: ValueEventListener? = null
    private var locationCallback: LocationCallback? = null
    private val heartbeatHandler = android.os.Handler(Looper.getMainLooper())
    private var heartbeatRunnable: Runnable? = null

    // Current active state — held in memory
    private var activeGeofenceId: String? = null
    private var activeGeofenceName: String = ""
    private var polygon: List<LatLng> = emptyList()

    // Breach dedup — true while device is continuously outside the fence
    private var isOutside: Boolean = false

    companion object {
        private const val TAG = "SkieGeofenceManager"
        private const val GEOFENCE_INTERVAL_MS = 30_000L
        private const val GEOFENCE_MIN_INTERVAL_MS = 20_000L
    }

    data class LatLng(val lat: Double, val lng: Double)

    fun start() {
        attachConfigListener()
        Log.d(TAG, "started for deviceId=$deviceId")
    }

    private fun attachConfigListener() {
        configListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) {
                    // Config node deleted — geofence turned off from dashboard
                    deactivate("config node absent")
                    return
                }

                val enabled = snapshot.child("enabled").getValue(Boolean::class.java) ?: false
                val geofenceId = snapshot.child("activeGeofenceId").getValue(String::class.java)
                val name = snapshot.child("geofenceName").getValue(String::class.java) ?: "Zone"

                // Parse polygon array from RTDB
                @Suppress("UNCHECKED_CAST")
                val rawPolygon = snapshot.child("polygon").children.mapNotNull { point ->
                    val lat = point.child("lat").getValue(Double::class.java)
                    val lng = point.child("lng").getValue(Double::class.java)
                    if (lat != null && lng != null) LatLng(lat, lng) else null
                }

                when {
                    enabled && geofenceId != null && rawPolygon.size >= 3 -> {
                        if (geofenceId != activeGeofenceId) {
                            // New zone or first boot activation
                            Log.d(TAG, "Geofence activated: $geofenceId ('$name') with ${rawPolygon.size} points")
                            activeGeofenceId = geofenceId
                            activeGeofenceName = name
                            polygon = rawPolygon
                            isOutside = false
                            startLocationUpdates()
                        }
                        // Same zone already active — no-op, polling continues
                    }
                    else -> {
                        deactivate("enabled=$enabled geofenceId=$geofenceId polygonPoints=${rawPolygon.size}")
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "geofenceConfig listener cancelled: ${error.message}")
            }
        }
        configRef.addValueEventListener(configListener!!)
    }

    private fun deactivate(reason: String) {
        if (activeGeofenceId != null) {
            Log.d(TAG, "Geofence deactivated ($reason) — stopping location updates")
            statusRef.updateChildren(mapOf(
                "isOutside"    to false,
                "outsideSince" to null
            ))
        }
        activeGeofenceId = null
        polygon = emptyList()
        isOutside = false
        stopLocationUpdates()
        stopHeartbeat()
    }

    // ── 30s heartbeat — writes lastCheckedAt regardless of location delivery ─

    private fun startHeartbeat() {
        stopHeartbeat()
        heartbeatRunnable = object : Runnable {
            override fun run() {
                statusRef.child("lastCheckedAt").setValue(System.currentTimeMillis())
                heartbeatHandler.postDelayed(this, GEOFENCE_INTERVAL_MS)
            }
        }
        heartbeatHandler.post(heartbeatRunnable!!)
        Log.d(TAG, "Heartbeat started")
    }

    private fun stopHeartbeat() {
        heartbeatRunnable?.let { heartbeatHandler.removeCallbacks(it) }
        heartbeatRunnable = null
    }

    // ── Location updates ──────────────────────────────────────────────────

    private fun startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.ACCESS_FINE_LOCATION
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "Location permission not granted — cannot start geofence polling")
            return
        }

        stopLocationUpdates() // clear any existing callback first

        val request = LocationRequest.Builder(GEOFENCE_INTERVAL_MS)
            .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
            .setMinUpdateIntervalMillis(GEOFENCE_MIN_INTERVAL_MS)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                // Write coordinates to geofenceStatus so dashboard pin updates
                statusRef.updateChildren(mapOf(
                    "latitude"       to loc.latitude,
                    "longitude"      to loc.longitude,
                    "lastLocationAt" to System.currentTimeMillis()
                ))
                checkGeofence(loc.latitude, loc.longitude)
            }
        }

        fusedClient.requestLocationUpdates(request, locationCallback!!, Looper.getMainLooper())
        startHeartbeat()
        Log.d(TAG, "Location updates started (${GEOFENCE_INTERVAL_MS / 1000}s interval) for '$activeGeofenceName'")
    }

    private fun stopLocationUpdates() {
        locationCallback?.let { fusedClient.removeLocationUpdates(it) }
        locationCallback = null
    }

    // ── Point-in-polygon (ray casting) ────────────────────────────────────

    private fun isPointInPolygon(lat: Double, lng: Double, poly: List<LatLng>): Boolean {
        if (poly.size < 3) return false
        var inside = false
        var j = poly.size - 1
        for (i in poly.indices) {
            val xi = poly[i].lng; val yi = poly[i].lat
            val xj = poly[j].lng; val yj = poly[j].lat
            val intersect = ((yi > lat) != (yj > lat)) &&
                    (lng < (xj - xi) * (lat - yi) / (yj - yi) + xi)
            if (intersect) inside = !inside
            j = i
        }
        return inside
    }

    private fun checkGeofence(lat: Double, lng: Double) {
        if (polygon.isEmpty()) return
        val inside = isPointInPolygon(lat, lng, polygon)
        when {
            !inside && !isOutside -> {
                // Transitioned inside → outside — fire breach alert + persist state
                isOutside = true
                val now = System.currentTimeMillis()
                statusRef.updateChildren(mapOf(
                    "isOutside"    to true,
                    "outsideSince" to now
                ))
                writeBreachAlert(lat, lng)
                Log.d(TAG, "BREACH: device left '$activeGeofenceName' at $lat,$lng")
            }
            inside && isOutside -> {
                // Returned inside — clear breach state
                isOutside = false
                statusRef.updateChildren(mapOf(
                    "isOutside"    to false,
                    "outsideSince" to null
                ))
                Log.d(TAG, "Device returned inside '$activeGeofenceName'")
            }
        }
    }

    private fun writeBreachAlert(lat: Double, lng: Double) {
        val alertId = alertsRef.push().key ?: return
        alertsRef.child(alertId).setValue(
            mapOf(
                "geofenceId"   to (activeGeofenceId ?: ""),
                "geofenceName" to activeGeofenceName,
                "deviceLat"    to lat,
                "deviceLng"    to lng,
                "timestamp"    to System.currentTimeMillis(),
                "seen"         to false
            )
        ).addOnSuccessListener {
            Log.d(TAG, "Breach alert written: $alertId")
        }.addOnFailureListener { e ->
            Log.e(TAG, "Failed to write breach alert: ${e.message}")
        }
    }

    fun stop() {
        configListener?.let { configRef.removeEventListener(it) }
        configListener = null
        stopLocationUpdates()
        stopHeartbeat()
        activeGeofenceId = null
        polygon = emptyList()
        isOutside = false
        Log.d(TAG, "stopped")
    }
}