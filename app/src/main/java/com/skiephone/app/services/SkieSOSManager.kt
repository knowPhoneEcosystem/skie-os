package com.skiephone.app.services

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.telecom.TelecomManager
import android.util.Log
import com.google.android.gms.location.LocationServices
import com.google.firebase.database.*
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class SkieSOSManager(
    private val context: Context,
    private val deviceId: String,
    private val travelManager: SkieTravelManager
) {

    private val db = FirebaseDatabase.getInstance()
    private val firestore = FirebaseFirestore.getInstance()
    private val sosRef = db.getReference("devices/$deviceId/sos")
    private val commandRef = db.getReference("devices/$deviceId/command")
    private var commandListener: ValueEventListener? = null
    private var sosStateListener: ValueEventListener? = null
    private var lastCommandAt: Long = -1L
    private val scope = CoroutineScope(Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())
    private var autoExpireRunnable: Runnable? = null

    fun startListening() {
        // Listen for LISTEN_CALL command from parent dashboard
        commandListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) return
                val action = snapshot.child("action").getValue(String::class.java) ?: return
                val issuedAt = snapshot.child("issuedAt").getValue(Long::class.java) ?: 0L
                if (action != "LISTEN_CALL" || issuedAt == lastCommandAt) return
                lastCommandAt = issuedAt
                Log.d(TAG, "LISTEN_CALL command received")
                scope.launch {
                    val phone = getParentPhone()
                    if (phone != null) placeSilentCall(phone, enableSpeaker = true)
                    else Log.e(TAG, "No parent phone number found for LISTEN_CALL")
                }
            }
            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "SOS command listener cancelled: ${error.message}")
            }
        }
        commandRef.addValueEventListener(commandListener!!)

        // Listen to SOS node — schedule auto-expiry when endsAt is present
        sosStateListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val active = snapshot.child("active").getValue(Boolean::class.java) ?: false
                val endsAt = snapshot.child("endsAt").getValue(Long::class.java) ?: 0L
                if (active && endsAt > 0L) {
                    scheduleAutoExpiry(endsAt)
                } else {
                    cancelAutoExpiry()
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        sosRef.addValueEventListener(sosStateListener!!)

        Log.d(TAG, "startListening: SOS manager active for deviceId=$deviceId")
    }

    /**
     * Trigger SOS from child with explicit wall-clock deadline.
     * Called from LauncherActivity after child picks duration.
     */
    @SuppressLint("MissingPermission")
    fun triggerSOS(endsAt: Long) {
        Log.d(TAG, "SOS triggered by student, endsAt=$endsAt")
        scope.launch {
            try {
                val loc = LocationServices.getFusedLocationProviderClient(context).lastLocation.await()
                val sosData = mutableMapOf<String, Any>(
                    "triggeredAt" to System.currentTimeMillis(),
                    "triggeredBy" to "student",
                    "active"      to true,
                    "endsAt"      to endsAt
                )
                if (loc != null) {
                    sosData["lat"] = loc.latitude
                    sosData["lng"] = loc.longitude
                }
                sosRef.setValue(sosData)
                travelManager.startSOSLocationUpdates()
                val phone = getParentPhone()
                if (phone != null) placeSilentCall(phone, enableSpeaker = true)
                else Log.w(TAG, "SOS: no parent phone number — skipping call")
            } catch (e: Exception) {
                Log.e(TAG, "SOS trigger error: ${e.message}")
            }
        }
        scheduleAutoExpiry(endsAt)
    }

    /**
     * Clear SOS — called by child (if canChildDisableSOS=true) or auto-expiry.
     */
    fun clearSOS() {
        cancelAutoExpiry()
        sosRef.updateChildren(mapOf(
            "active"    to false,
            "clearedAt" to System.currentTimeMillis()
        ))
        travelManager.stopSOSLocationUpdates()
        Log.d(TAG, "SOS cleared")
    }

    // ── Auto-expiry ───────────────────────────────────────────────────────────

    private fun scheduleAutoExpiry(endsAt: Long) {
        cancelAutoExpiry()
        val delay = endsAt - System.currentTimeMillis()
        if (delay <= 0L) { clearSOS(); return }
        autoExpireRunnable = Runnable {
            Log.d(TAG, "SOS auto-expired at endsAt=$endsAt")
            clearSOS()
        }
        handler.postDelayed(autoExpireRunnable!!, delay)
        Log.d(TAG, "SOS auto-expiry scheduled in ${delay / 1000}s")
    }

    private fun cancelAutoExpiry() {
        autoExpireRunnable?.let { handler.removeCallbacks(it) }
        autoExpireRunnable = null
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Gets parent phone number for SOS calls.
     *
     * Strategy (offline-safe):
     *   1. Try to read fresh from Firestore users/{parentId}/phone — works because
     *      device now has a deviceAuth/{anonUid} doc that Firestore rules use to
     *      verify device identity and grant read access to the parent's user doc.
     *   2. On success — cache the number in SharedPreferences for offline use.
     *   3. If Firestore read fails (no internet) — fall back to SharedPreferences cache.
     *
     * parentId source: RTDB deviceClaims/{deviceId}/parentId — read once, held in memory.
     */
    private suspend fun getParentPhone(): String? {
        // Step 1: Get parentId from RTDB deviceClaims
        return try {
            val parentId = FirebaseDatabase.getInstance()
                .getReference("deviceClaims/$deviceId/parentId")
                .get().await()
                .getValue(String::class.java)

            if (parentId == null) {
                Log.w(TAG, "getParentPhone: parentId not found in RTDB — device may not be paired")
                return getCachedPhone()
            }

            // Step 2: Read phone from Firestore users/{parentId} directly
            // Allowed by Firestore rule: deviceAuth/{anonUid}.parentId == userId
            val phone = try {
                val snap = firestore.collection("users").document(parentId).get().await()
                snap.getString("phone")
            } catch (e: Exception) {
                Log.w(TAG, "getParentPhone: Firestore read failed (${e.message}) — using cache")
                null
            }

            if (phone != null) {
                // Step 3: Cache for offline use
                cachePhone(phone)
                Log.d(TAG, "getParentPhone: fresh number from Firestore, cached")
                phone
            } else {
                // Firestore returned null or failed — use cache
                getCachedPhone()
            }
        } catch (e: Exception) {
            Log.e(TAG, "getParentPhone error: ${e.message} — using cache")
            getCachedPhone()
        }
    }

    private fun cachePhone(phone: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_PHONE, phone).apply()
    }

    private fun getCachedPhone(): String? {
        val cached = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PHONE, null)
        if (cached != null) Log.d(TAG, "getParentPhone: using cached number")
        else Log.w(TAG, "getParentPhone: no cached number — SOS call cannot proceed")
        return cached
    }

    @SuppressLint("MissingPermission")
    private fun placeSilentCall(phoneNumber: String, enableSpeaker: Boolean) {
        try {
            val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            val uri = Uri.fromParts("tel", phoneNumber, null)
            val extras = Bundle()
            extras.putBoolean(TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE, enableSpeaker)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                telecomManager.placeCall(uri, extras)
                Log.d(TAG, "Silent call placed to $phoneNumber, speaker=$enableSpeaker")
                if (enableSpeaker) {
                    Handler(Looper.getMainLooper()).postDelayed({
                        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                        audio.isSpeakerphoneOn = true
                        audio.mode = AudioManager.MODE_IN_CALL
                        Log.d(TAG, "Speakerphone enforced")
                    }, 2000)
                }
            } else {
                Log.w(TAG, "placeCall requires API 23+, skipping")
            }
        } catch (e: Exception) {
            Log.e(TAG, "placeSilentCall error: ${e.message}")
        }
    }

    fun stop() {
        cancelAutoExpiry()
        commandListener?.let { commandRef.removeEventListener(it) }
        sosStateListener?.let { sosRef.removeEventListener(it) }
        commandListener = null
        sosStateListener = null
        Log.d(TAG, "stop: SOS manager stopped")
    }

    companion object {
        private const val TAG = "SkieSOSManager"
        private const val PREFS = "skie_sos"
        private const val KEY_PHONE = "parent_phone"
    }
}