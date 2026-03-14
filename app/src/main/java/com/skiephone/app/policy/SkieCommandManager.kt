package com.skiephone.app.policy

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.skiephone.app.admin.SkieDeviceAdminReceiver

class SkieCommandManager(private val context: Context) {

    companion object {
        const val TAG = "SkieCommandManager"
    }

    private val database = FirebaseDatabase.getInstance()
    private val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE)
            as DevicePolicyManager
    private val componentName = ComponentName(
        context,
        SkieDeviceAdminReceiver::class.java
    )
    private val deviceId: String by lazy {
        android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        )
    }

    @Volatile private var lastExecutedIssuedAt: Long = -1L

    fun startListening() {
        val commandRef = database.getReference("devices/$deviceId/command")
        commandRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) return

                val action = snapshot.child("action")
                    .getValue(String::class.java)
                    ?.uppercase()
                    ?.trim()
                    .orEmpty()

                if (action.isBlank()) return

                // Use issuedAt as dedup key — same timestamp = same command dispatch, skip it
                val issuedAt = snapshot.child("issuedAt")
                    .getValue(Long::class.java) ?: 0L

                if (issuedAt != 0L && issuedAt == lastExecutedIssuedAt) {
                    Log.d(TAG, "Skipping duplicate command (issuedAt=$issuedAt): $action")
                    return
                }

                Log.d(TAG, "Command received: $action (issuedAt=$issuedAt)")
                lastExecutedIssuedAt = issuedAt

                when (action) {
                    "REMOVE_DEVICE_OWNER" -> removeDeviceOwner()
                    "FACTORY_RESET" -> factoryReset()
                    else -> Log.w(TAG, "Unknown command: $action")
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Command listener cancelled: ${error.message}")
            }
        })
    }

    private fun removeDeviceOwner() {
        try {
            SkiePolicyCache.clearAll(context)

            if (!dpm.isDeviceOwnerApp(context.packageName)) {
                Log.w(TAG, "Not device owner — nothing to clear")
                database.getReference("devices/$deviceId/command").removeValue()
                return
            }

            dpm.clearDeviceOwnerApp(context.packageName)
            Log.d(TAG, "Device Owner removed successfully")

            database.getReference("devices/$deviceId/online").setValue(false)
            database.getReference("devices/$deviceId/status").setValue("unmanaged")
            database.getReference("devices/$deviceId/command").removeValue()

            context.stopService(
                android.content.Intent(
                    context,
                    com.skiephone.app.services.SkiePolicyService::class.java
                )
            )

        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove device owner: ${e.message}")
            lastExecutedIssuedAt = -1L
        }
    }

    private fun factoryReset() {
        try {
            Log.d(TAG, "Factory reset initiated")
            dpm.wipeData(0)
        } catch (e: Exception) {
            Log.e(TAG, "Factory reset failed: ${e.message}")
            lastExecutedIssuedAt = -1L
        }
    }
}