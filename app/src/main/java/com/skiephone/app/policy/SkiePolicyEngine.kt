package com.skiephone.app.policy

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.UserManager
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
        grantRequiredPermissions()
        hardenDevice()
        applyPolicyFromCache()
        suspendBlacklistedApps()

        val policyRef = FirebaseDatabase.getInstance().reference
            .child("devices").child(deviceId).child("policy")
        policyRef.keepSynced(true)
        policyRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                Log.d(TAG, "Policy updated from Firebase")
                applyPolicy(snapshot)
            }
            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Policy listener cancelled", error.toException())
            }
        })

        val categoriesRef = FirebaseDatabase.getInstance().reference
            .child("devices").child(deviceId).child("enabledCategories")
        categoriesRef.keepSynced(true)
        categoriesRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                Log.d(TAG, "Categories updated from Firebase")
                applyCategories(snapshot)
            }
            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Categories listener cancelled", error.toException())
            }
        })
    }

    private fun applyPolicy(snapshot: DataSnapshot) {
        val cameraDisabled = snapshot.child("cameraDisabled").getValue(Boolean::class.java) ?: false
        val schoolModeEnabled = snapshot.child("schoolModeEnabled").getValue(Boolean::class.java) ?: false
        val screenLockEnabled = snapshot.child("screenLockEnabled").getValue(Boolean::class.java) ?: false

        Log.d(TAG, "Screen lock enabled: $screenLockEnabled")
        SkiePolicyCache.save(context, cameraDisabled, schoolModeEnabled, screenLockEnabled)
        setCameraDisabled(cameraDisabled)
        setSchoolMode(schoolModeEnabled)
        if (screenLockEnabled) lockScreen()

        SkiePolicyState.notifyUpdated()
    }

    private fun applyCategories(snapshot: DataSnapshot) {
        val essentialAppsEnabled = snapshot.child("essentialApps").getValue(Boolean::class.java) ?: true
        val schoolAppsEnabled = snapshot.child("schoolApps").getValue(Boolean::class.java) ?: true

        Log.d(TAG, "Essential apps: $essentialAppsEnabled, School apps: $schoolAppsEnabled")
        SkiePolicyCache.saveCategories(context, essentialAppsEnabled, schoolAppsEnabled)

        // Re-suspend based on new categories
        suspendBlacklistedApps()

        SkiePolicyState.notifyUpdated()
    }

    private fun applyPolicyFromCache() {
        val cameraDisabled = SkiePolicyCache.getCameraDisabled(context)
        val schoolModeEnabled = SkiePolicyCache.getSchoolModeEnabled(context)
        val screenLockEnabled = SkiePolicyCache.getScreenLockEnabled(context)
        Log.d(TAG, "Applying cached policy — camera: $cameraDisabled, school: $schoolModeEnabled, lock: $screenLockEnabled")
        setCameraDisabled(cameraDisabled)
        setSchoolMode(schoolModeEnabled)
        if (screenLockEnabled) lockScreen()
    }

    fun suspendBlacklistedApps() {
        if (!isDeviceOwner()) {
            Log.d(TAG, "Not device owner — skipping app suspension")
            return
        }

        // Build current allowed list based on cache
        val allowed = mutableSetOf<String>()
        allowed.addAll(SkieConfig.systemPackages)
        allowed.addAll(SkieConfig.dialerPackages)
        allowed.addAll(SkieConfig.messagingPackages)
        if (SkiePolicyCache.getEssentialAppsEnabled(context)) allowed.addAll(SkieConfig.essentialApps)
        if (SkiePolicyCache.getSchoolAppsEnabled(context)) allowed.addAll(SkieConfig.schoolApps)
        if (!SkiePolicyCache.getCameraDisabled(context)) allowed.addAll(SkieConfig.cameraApps)
        allowed.add(context.packageName)

        // Get all installed packages
        val pm = context.packageManager
        val installedPackages = pm.getInstalledPackages(0).map { it.packageName }

        // Suspend anything not in allowed list and not a system app
        val toSuspend = installedPackages.filter { pkg ->
            !allowed.contains(pkg) && !isSystemApp(pkg)
        }

        val toUnsuspend = installedPackages.filter { pkg ->
            allowed.contains(pkg)
        }

        try {
            if (toSuspend.isNotEmpty()) {
                devicePolicyManager.setPackagesSuspended(
                    adminComponent,
                    toSuspend.toTypedArray(),
                    true
                )
                Log.d(TAG, "Suspended ${toSuspend.size} blacklisted apps")
            }
            if (toUnsuspend.isNotEmpty()) {
                devicePolicyManager.setPackagesSuspended(
                    adminComponent,
                    toUnsuspend.toTypedArray(),
                    false
                )
                Log.d(TAG, "Unsuspended ${toUnsuspend.size} allowed apps")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to suspend packages: ${e.message}")
        }
    }

    private fun isSystemApp(packageName: String): Boolean {
        return try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
        } catch (e: Exception) {
            true // If we can't check, assume system app and leave it alone
        }
    }

    private fun hardenDevice() {
        if (!isDeviceOwner()) {
            Log.d(TAG, "Not device owner — skipping device hardening")
            return
        }
        devicePolicyManager.setUninstallBlocked(adminComponent, context.packageName, true)
        Log.d(TAG, "Skie uninstall blocked")
        SkieConfig.whitelistedPackages.forEach { packageName ->
            try {
                devicePolicyManager.setUninstallBlocked(adminComponent, packageName, true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to block uninstall for: $packageName", e)
            }
        }
        devicePolicyManager.addUserRestriction(adminComponent, UserManager.DISALLOW_FACTORY_RESET)
        devicePolicyManager.addUserRestriction(adminComponent, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
        devicePolicyManager.addUserRestriction(adminComponent, UserManager.DISALLOW_MODIFY_ACCOUNTS)
        devicePolicyManager.addUserRestriction(adminComponent, UserManager.DISALLOW_USB_FILE_TRANSFER)
        devicePolicyManager.addUserRestriction(adminComponent, UserManager.DISALLOW_SAFE_BOOT)
        Log.d(TAG, "Device hardening complete")
    }

    private fun grantRequiredPermissions() {
        if (!isDeviceOwner()) {
            Log.d(TAG, "Not device owner — skipping silent permission grant")
            return
        }
        val permissions = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        permissions.forEach { permission ->
            try {
                devicePolicyManager.setPermissionGrantState(
                    adminComponent,
                    context.packageName,
                    permission,
                    DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
                )
                Log.d(TAG, "Permission granted: $permission")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to grant permission: $permission", e)
            }
        }
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