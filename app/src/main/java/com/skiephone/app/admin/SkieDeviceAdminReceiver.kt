package com.skiephone.app.admin

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import com.skiephone.app.services.SkiePolicyService

class SkieDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.d(TAG, "Device Admin enabled — pinning launcher and starting service")
        pinLauncher(context)
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
        Log.d(TAG, "Profile provisioning complete — pinning launcher and starting service")

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

        pinLauncher(context)
        context.startForegroundService(Intent(context, SkiePolicyService::class.java))
        Log.d(TAG, "SkiePolicyService started from provisioning complete")
    }

    /**
     * Pin SkieLauncher as persistent preferred HOME activity via DPM.
     * Called on both onEnabled and onProfileProvisioningComplete so launcher
     * is always pinned immediately when device owner is set — no home button press needed.
     */
    private fun pinLauncher(context: Context) {
        try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = ComponentName(context, SkieDeviceAdminReceiver::class.java)
            if (!dpm.isDeviceOwnerApp(context.packageName)) return
            val filter = IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addCategory(Intent.CATEGORY_DEFAULT)
            }
            val launcherComponent = ComponentName(
                context.packageName,
                "${context.packageName}.launcher.LauncherActivity"
            )
            dpm.clearPackagePersistentPreferredActivities(admin, context.packageName)
            dpm.addPersistentPreferredActivity(admin, filter, launcherComponent)
            Log.d(TAG, "pinLauncher: launcher pinned as persistent home")
        } catch (e: Exception) {
            Log.e(TAG, "pinLauncher failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "SkieAdminReceiver"
    }
}