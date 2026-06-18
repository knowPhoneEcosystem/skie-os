package com.skiephone.app.admin

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import com.skiephone.app.services.SkiePolicyService

class SkieDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.d(TAG, "Device Admin enabled — starting service")
        context.startForegroundService(Intent(context, SkiePolicyService::class.java))
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.d(TAG, "Device Admin disabled")
    }

    /**
     * Called when QR/NFC provisioning completes and this app is set as Device Owner.
     * Must start SkiePolicyService here — this is the entry point after QR enrollment.
     */
    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        super.onProfileProvisioningComplete(context, intent)
        Log.d(TAG, "Profile provisioning complete — starting service")

        try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = ComponentName(context, SkieDeviceAdminReceiver::class.java)
            if (dpm.isDeviceOwnerApp(context.packageName)) {
                dpm.setOrganizationName(admin, "SkiePhone")
                Log.d(TAG, "Device owner confirmed — organisation name set")
            }
        } catch (e: Exception) {
            Log.e(TAG, "onProfileProvisioningComplete setup: ${e.message}")
        }

        context.startForegroundService(Intent(context, SkiePolicyService::class.java))
        Log.d(TAG, "SkiePolicyService started from provisioning complete")
    }

    companion object {
        private const val TAG = "SkieAdminReceiver"
    }
}