package com.skiephone.app.admin

import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.skiephone.app.policy.SkiePolicyEngine

/**
 * Fired by AlarmManager when the Play Store tunnel timer expires.
 * Works offline — AlarmManager fires regardless of network state.
 * Suspends Play Store + removes it from effectiveApps.
 */
class PlayStoreTunnelReceiver : BroadcastReceiver() {

    companion object {
        const val TAG = "PlayStoreTunnelReceiver"
        const val ACTION_TUNNEL_EXPIRED = "com.skiephone.app.PLAY_STORE_TUNNEL_EXPIRED"
        const val PREFS_NAME = "skie_play_store_tunnel"
        const val PREF_TUNNEL_END_MS = "tunnel_end_ms"

        /**
         * Check on boot/service start if a tunnel was active when device was offline.
         * If tunnel has expired, close it immediately.
         */
        fun checkAndCloseStaleTunnel(context: Context) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val tunnelEndMs = prefs.getLong(PREF_TUNNEL_END_MS, 0L)
            if (tunnelEndMs > 0 && System.currentTimeMillis() >= tunnelEndMs) {
                Log.d(TAG, "checkAndCloseStaleTunnel: stale tunnel found — closing now")
                closeTunnel(context)
            }
        }

        fun closeTunnel(context: Context) {
            val deviceId = android.provider.Settings.Secure.getString(
                context.contentResolver, android.provider.Settings.Secure.ANDROID_ID
            )
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = ComponentName(context, SkieDeviceAdminReceiver::class.java)

            // 1. Suspend Play Store
            try {
                if (dpm.isDeviceOwnerApp(context.packageName)) {
                    dpm.setPackagesSuspended(admin, arrayOf(SkiePolicyEngine.PLAY_STORE_PKG), true)
                    Log.d(TAG, "closeTunnel: Play Store suspended")
                }
            } catch (e: Exception) {
                Log.e(TAG, "closeTunnel: suspend failed — ${e.message}")
            }

            // 2. Remove Play Store from effectiveApps in RTDB
            val rtdb = FirebaseDatabase.getInstance()
            val effectiveAppsRef = rtdb.getReference("devices/$deviceId/effectiveApps")
            effectiveAppsRef.get().addOnSuccessListener { snapshot ->
                val updated = mutableListOf<Map<String, Any>>()
                snapshot.children.forEach { child ->
                    val pkg = child.child("packageName").getValue(String::class.java) ?: return@forEach
                    if (pkg != SkiePolicyEngine.PLAY_STORE_PKG) {
                        updated.add(mapOf(
                            "packageName" to pkg,
                            "appName" to (child.child("appName").getValue(String::class.java) ?: pkg),
                            "category" to (child.child("category").getValue(String::class.java) ?: "other")
                        ))
                    }
                }
                effectiveAppsRef.setValue(updated)
                Log.d(TAG, "closeTunnel: Play Store removed from effectiveApps")
            }

            // 3. Update RTDB tunnel status
            rtdb.getReference("devices/$deviceId/playStoreTunnel").setValue(false)
            rtdb.getReference("devices/$deviceId/playStoreTunnelEndsAt").setValue(0)

            // 4. Clear SharedPreferences
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().remove(PREF_TUNNEL_END_MS).apply()

            Log.d(TAG, "closeTunnel: complete")
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TUNNEL_EXPIRED) return
        Log.d(TAG, "onReceive: tunnel expired alarm fired")
        closeTunnel(context)
    }
}