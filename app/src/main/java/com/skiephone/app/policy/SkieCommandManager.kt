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
import com.skiephone.app.policy.SkiePolicyCache
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

    fun startListening() {
        val commandRef = database.getReference("devices/$deviceId/command")
        commandRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val action = snapshot.child("action")
                    .getValue(String::class.java) ?: return
                Log.d(TAG, "Command received: $action")
                when (action) {
                    "REMOVE_DEVICE_OWNER" -> removeDeviceOwner()
                    "FACTORY_RESET" -> factoryReset()
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

            // 1. Disable accessibility FIRST (while still device owner)
            dpm.setSecureSetting(
                componentName,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                ""
            )

            // 2. Now remove device owner
            dpm.clearDeviceOwnerApp(context.packageName)
            Log.d(TAG, "Device Owner removed successfully")

            database.getReference("devices/$deviceId/online").setValue(false)
            database.getReference("devices/$deviceId/status").setValue("unmanaged")
            database.getReference("devices/$deviceId/command").removeValue()

            // 3. Stop the service
            context.stopService(
                android.content.Intent(context, com.skiephone.app.services.SkiePolicyService::class.java)
            )

        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove device owner: ${e.message}")
        }
    }

    private fun factoryReset() {
        try {
            Log.d(TAG, "Factory reset initiated")
            dpm.wipeData(0)
        } catch (e: Exception) {
            Log.e(TAG, "Factory reset failed: ${e.message}")
        }
    }
}