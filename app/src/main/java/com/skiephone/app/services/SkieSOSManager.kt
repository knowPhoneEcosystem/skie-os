package com.skiephone.app.services

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
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

/**
 * Handles two scenarios:
 * 1. Student triggers SOS → writes to RTDB + calls parent silently
 * 2. Parent triggers listen call from dashboard → device receives RTDB command → calls parent silently
 *
 * Both enable speakerphone automatically.
 * Requires CALL_PHONE permission granted by Device Owner (done in SkiePolicyEngine.hardenDevice).
 */
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
    private var lastCommandAt: Long = -1L

    private val scope = CoroutineScope(Dispatchers.IO)

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
                    if (phone != null) {
                        placeSilentCall(phone, enableSpeaker = true)
                    } else {
                        Log.e(TAG, "No parent phone number found for LISTEN_CALL")
                    }
                }
            }
            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "SOS command listener cancelled: ${error.message}")
            }
        }
        commandRef.addValueEventListener(commandListener!!)
        Log.d(TAG, "startListening: SOS manager active for deviceId=$deviceId")
    }

    /**
     * Called when student taps the SOS button.
     * 1. Gets current location
     * 2. Writes SOS event to RTDB
     * 3. Places silent call to parent with speakerphone
     * 4. Activates high-frequency location automatically via travelMode node
     */
    @SuppressLint("MissingPermission")
    fun triggerSOS() {
        Log.d(TAG, "SOS triggered by student")
        scope.launch {
            try {
                // 1. Get location
                val loc = LocationServices.getFusedLocationProviderClient(context).lastLocation.await()

                // 2. Write SOS to RTDB
                val sosData = mutableMapOf<String, Any>(
                    "triggeredAt" to System.currentTimeMillis(),
                    "active" to true
                )
                if (loc != null) {
                    sosData["lat"] = loc.latitude
                    sosData["lng"] = loc.longitude
                }
                db.getReference("devices/$deviceId/sos").setValue(sosData)

                // 3. Start high-frequency location via travelManager (SOS mode)
                travelManager.startSOSLocationUpdates()

                // 4. Call parent
                val phone = getParentPhone()
                if (phone != null) {
                    placeSilentCall(phone, enableSpeaker = true)
                } else {
                    Log.w(TAG, "SOS: no parent phone number — skipping call")
                }

            } catch (e: Exception) {
                Log.e(TAG, "SOS trigger error: ${e.message}")
            }
        }
    }

    /**
     * Clears active SOS — called when parent dismisses the alert on dashboard.
     */
    fun clearSOS() {
        db.getReference("devices/$deviceId/sos").updateChildren(
            mapOf("active" to false, "clearedAt" to System.currentTimeMillis())
        )
    }

    /**
     * Fetches parent phone number from Firestore users/{parentId}/phone.
     * parentId is read from deviceClaims/{deviceId}/parentId.
     */
    private suspend fun getParentPhone(): String? {
        return try {
            val claimSnap = firestore.collection("deviceClaims").document(deviceId).get().await()
            val parentId = claimSnap.getString("parentId") ?: return null
            val userSnap = firestore.collection("users").document(parentId).get().await()
            userSnap.getString("phone")
        } catch (e: Exception) {
            Log.e(TAG, "getParentPhone error: ${e.message}")
            null
        }
    }

    /**
     * Places a silent call using TelecomManager (Device Owner privilege).
     * Works without showing the dialer UI.
     * enableSpeaker: true puts call on speakerphone immediately.
     */
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

                // Additional speakerphone enforcement after short delay
                if (enableSpeaker) {
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
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
        commandListener?.let { commandRef.removeEventListener(it) }
        commandListener = null
        Log.d(TAG, "stop: SOS manager stopped")
    }

    companion object {
        private const val TAG = "SkieSOSManager"
    }
}