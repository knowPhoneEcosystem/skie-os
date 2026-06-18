package com.skiephone.app.auth

import android.content.Context
import android.provider.Settings
import android.util.Log
import android.os.Handler
import android.os.Looper
import com.google.firebase.database.FirebaseDatabase
import com.skiephone.app.util.SkiePrefManager

/**
 * Manages PIN and TOTP auth state against RTDB.
 * All PIN storage and validation is RTDB-only — no local PIN copy.
 * TOTP secret is stored in both RTDB and EncryptedSharedPrefs.
 */
object SkieAuthManager {

    private const val TAG = "SkieAuthManager"

    fun getDeviceId(context: Context): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)

    /**
     * Returns true if a PIN exists in RTDB for this device. Callback on main thread.
     *
     * CRITICAL: never guesses. The PIN is changeable remotely from the dashboard
     * (including being reset/cleared), so there is no safe local default and no
     * safe timeout-based default in either direction — a wrong guess either
     * silently bypasses an existing PIN (routes to setup) or locks the parent
     * out of a cleared PIN (routes to entry with no way back to setup). Instead,
     * this retries with backoff until it gets a real, confirmed answer from
     * Firebase. onResult is only ever called with a confirmed exists/not-exists
     * value. onGiveUp is called if every retry attempt is exhausted without an
     * answer — the caller should show an explicit error + manual retry UI, never
     * proceed to either PIN_SETUP or PIN_ENTRY on its own.
     */
    fun isPinSetup(
        context: Context,
        onResult: (Boolean) -> Unit,
        onRetrying: ((attempt: Int) -> Unit)? = null,
        onGiveUp: (() -> Unit)? = null
    ) {
        checkExistsWithRetry(
            context, "adminPin",
            onResult = onResult,
            onRetrying = onRetrying,
            onGiveUp = onGiveUp
        )
    }

    /**
     * Returns true if TOTP secret exists in RTDB for this device.
     * Same no-guessing retry-with-backoff contract as isPinSetup — see its
     * docstring. TOTP enrollment can also be reset remotely, so the same
     * reasoning applies: never default to a guess on timeout or failure.
     */
    fun isTotpEnrolled(
        context: Context,
        onResult: (Boolean) -> Unit,
        onRetrying: ((attempt: Int) -> Unit)? = null,
        onGiveUp: (() -> Unit)? = null
    ) {
        checkExistsWithRetry(
            context, "totpSecret",
            onResult = onResult,
            onRetrying = onRetrying,
            onGiveUp = onGiveUp
        )
    }

    /**
     * Shared retry-with-backoff implementation for existence checks that must
     * never guess. Backoff: 2s, 4s, 8s, 16s, then repeats at 16s. Gives up after
     * ~45s of total elapsed retrying (6 attempts), at which point onGiveUp is
     * invoked exactly once and no further automatic retries occur — the caller
     * is expected to offer a manual "Retry" action that calls this again.
     */
    private fun checkExistsWithRetry(
        context: Context,
        nodeName: String,
        onResult: (Boolean) -> Unit,
        onRetrying: ((attempt: Int) -> Unit)?,
        onGiveUp: (() -> Unit)?
    ) {
        val deviceId = getDeviceId(context)
        val handler = Handler(Looper.getMainLooper())
        val backoffSchedule = longArrayOf(2000, 4000, 8000, 16000, 16000, 16000)
        var attempt = 0
        var settled = false

        fun attemptCheck() {
            if (settled) return
            FirebaseDatabase.getInstance()
                .getReference("devices/$deviceId/$nodeName")
                .get()
                .addOnSuccessListener { snap ->
                    if (!settled) {
                        settled = true
                        onResult(snap.exists() && snap.getValue(String::class.java)?.isNotBlank() == true)
                    }
                }
                .addOnFailureListener { e ->
                    if (settled) return@addOnFailureListener
                    Log.w(TAG, "$nodeName check attempt $attempt failed: ${e.message}")
                    if (attempt >= backoffSchedule.size) {
                        settled = true
                        Log.e(TAG, "$nodeName check: giving up after $attempt attempts")
                        onGiveUp?.invoke()
                        return@addOnFailureListener
                    }
                    val delayMs = backoffSchedule[attempt]
                    attempt++
                    onRetrying?.invoke(attempt)
                    handler.postDelayed({ attemptCheck() }, delayMs)
                }
        }
        attemptCheck()
    }

    /**
     * Validate PIN against RTDB. Callback: true = match, false = wrong, null = network error.
     */
    fun validatePin(context: Context, enteredPin: String, onResult: (Boolean?) -> Unit) {
        val deviceId = getDeviceId(context)
        FirebaseDatabase.getInstance()
            .getReference("devices/$deviceId/adminPin")
            .get()
            .addOnSuccessListener { snap ->
                val storedPin = snap.getValue(String::class.java)
                onResult(storedPin == enteredPin)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "validatePin failed: ${e.message}")
                onResult(null)
            }
    }

    /**
     * Save PIN to RTDB under devices/{deviceId}/adminPin.
     */
    fun savePin(context: Context, pin: String, onSuccess: () -> Unit, onFailure: (String) -> Unit) {
        val deviceId = getDeviceId(context)
        FirebaseDatabase.getInstance()
            .getReference("devices/$deviceId/adminPin")
            .setValue(pin)
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener { e ->
                Log.e(TAG, "savePin failed: ${e.message}")
                onFailure(e.message ?: "Failed to save PIN")
            }
    }

    /**
     * Save TOTP secret to RTDB and local EncryptedSharedPrefs.
     */
    fun saveTotpSecret(
        context: Context,
        secret: String,
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit
    ) {
        val deviceId = getDeviceId(context)
        // Save locally first (instant)
        SkiePrefManager.saveTotpSecret(context, secret)
        // Then sync to RTDB
        FirebaseDatabase.getInstance()
            .getReference("devices/$deviceId/totpSecret")
            .setValue(secret)
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener { e ->
                Log.e(TAG, "saveTotpSecret RTDB failed: ${e.message}")
                onFailure(e.message ?: "Failed to save TOTP secret")
            }
    }

    /**
     * Fetch TOTP secret — local first, RTDB fallback.
     * Used when re-displaying QR (lost authenticator scenario).
     */
    fun getTotpSecret(context: Context, onResult: (String?) -> Unit) {
        val local = SkiePrefManager.getTotpSecret(context)
        if (!local.isNullOrBlank()) {
            onResult(local)
            return
        }
        val deviceId = getDeviceId(context)
        FirebaseDatabase.getInstance()
            .getReference("devices/$deviceId/totpSecret")
            .get()
            .addOnSuccessListener { snap ->
                val secret = snap.getValue(String::class.java)
                if (!secret.isNullOrBlank()) {
                    SkiePrefManager.saveTotpSecret(context, secret) // restore local copy
                }
                onResult(secret)
            }
            .addOnFailureListener {
                onResult(null)
            }
    }

    /**
     * Validate TOTP code against locally-cached or RTDB secret.
     * Callback: true = valid, false = invalid, null = secret not found.
     */
    fun validateTotp(context: Context, code: String, onResult: (Boolean?) -> Unit) {
        getTotpSecret(context) { secret ->
            if (secret.isNullOrBlank()) {
                onResult(null)
                return@getTotpSecret
            }
            onResult(SkieTotpManager.verify(secret, code))
        }
    }

    /**
     * Clear PIN and TOTP from RTDB (called from owner dashboard reset command).
     * Local TOTP secret also cleared.
     */
    fun resetAuth(context: Context, onSuccess: () -> Unit, onFailure: (String) -> Unit) {
        val deviceId = getDeviceId(context)
        val db = FirebaseDatabase.getInstance()
        SkiePrefManager.clearTotpSecret(context)
        db.getReference("devices/$deviceId/adminPin").removeValue()
        db.getReference("devices/$deviceId/totpSecret")
            .removeValue()
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener { e -> onFailure(e.message ?: "Reset failed") }
    }

    /**
     * Change PIN — validates current PIN first, then writes new PIN to RTDB.
     */
    fun changePin(
        context: Context,
        currentPin: String,
        newPin: String,
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit
    ) {
        validatePin(context, currentPin) { valid ->
            when (valid) {
                true -> savePin(context, newPin, onSuccess, onFailure)
                false -> onFailure("Current PIN is incorrect")
                null -> onFailure("Network error. Please check your connection.")
            }
        }
    }
}