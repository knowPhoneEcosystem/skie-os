package com.skiephone.app.policy

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.skiephone.app.admin.SkieDeviceAdminReceiver

class SkiePolicyEngine(private val context: Context) {

    private val devicePolicyManager =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    private val adminComponent = ComponentName(context, SkieDeviceAdminReceiver::class.java)

    private val deviceId = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ANDROID_ID
    )

    fun startListening() {
        FirebaseDatabase.getInstance().reference
            .child("devices")
            .child(deviceId)
            .child("policy")
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    Log.d(TAG, "Policy updated from Firebase")
                    applyPolicy(snapshot)
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "Policy listener cancelled", error.toException())
                }
            })
    }

    private fun applyPolicy(snapshot: DataSnapshot) {
        val cameraDisabled = snapshot.child("cameraDisabled").getValue(Boolean::class.java) ?: false
        val schoolModeEnabled = snapshot.child("schoolModeEnabled").getValue(Boolean::class.java) ?: false
        val screenLockEnabled = snapshot.child("screenLockEnabled").getValue(Boolean::class.java) ?: false

        setCameraDisabled(cameraDisabled)
        setSchoolMode(schoolModeEnabled)
        if (screenLockEnabled) lockScreen()
    }

    fun setCameraDisabled(disabled: Boolean) {
        if (!isDeviceOwner()) return
        devicePolicyManager.setCameraDisabled(adminComponent, disabled)
        Log.d(TAG, "Camera disabled: $disabled")
    }

    fun lockScreen() {
        if (!isDeviceOwner()) return
        devicePolicyManager.lockNow()
        Log.d(TAG, "Screen locked")
    }

    fun setSchoolMode(enabled: Boolean) {
        if (!isDeviceOwner()) return
        if (enabled) {
            devicePolicyManager.setKeyguardDisabledFeatures(
                adminComponent,
                DevicePolicyManager.KEYGUARD_DISABLE_FEATURES_ALL
            )
        } else {
            devicePolicyManager.setKeyguardDisabledFeatures(
                adminComponent,
                DevicePolicyManager.KEYGUARD_DISABLE_FEATURES_NONE
            )
        }
        Log.d(TAG, "School mode enabled: $enabled")
    }

    fun isDeviceOwner(): Boolean {
        return devicePolicyManager.isDeviceOwnerApp(context.packageName)
    }

    fun requestDeviceAdmin() {
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Skie requires device admin to manage policies.")
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    companion object {
        private const val TAG = "SkiePolicyEngine"
    }
}