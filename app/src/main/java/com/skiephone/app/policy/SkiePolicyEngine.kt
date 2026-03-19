package com.skiephone.app.policy

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import com.google.firebase.database.*
import com.google.firebase.firestore.FirebaseFirestore
import com.skiephone.app.admin.SkieDeviceAdminReceiver
import com.skiephone.app.services.SkieInstalledAppsManager

class SkiePolicyEngine(private val context: Context) {

    private val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val admin = ComponentName(context, SkieDeviceAdminReceiver::class.java)
    private val deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
    private val rtdb = FirebaseDatabase.getInstance()

    // effectiveApps from RTDB — null means "not set yet, use globalPolicy"
    private var effectivePackages: Set<String>? = null

    // allowedPackages — subset of effectivePackages where allowed=true (toggle ON by parent)
    // null means "use effectivePackages as-is"
    private var allowedPackages: Set<String>? = null

    // School mode — when true, only school-approved packages are unsuspended
    private var _schoolModeEnabled: Boolean = false

    // Currently active focus app — always unsuspended regardless of effectivePackages
    private var activeFocusApp: String? = null

    private var effectiveAppsListener: ValueEventListener? = null
    private var policyListener: ValueEventListener? = null

    fun start() {
        Log.d(TAG, "start() deviceId:$deviceId isDeviceOwner:${isDeviceOwner()}")
        grantRequiredPermissions()
        hardenDevice()
        clearStaleSuspensions()
        listenGlobalPolicy()
    }

    fun stop() {
        effectiveAppsListener?.let { rtdb.getReference("devices/$deviceId/effectiveApps").removeEventListener(it) }
        policyListener?.let { rtdb.getReference("devices/$deviceId/policy").removeEventListener(it) }
        Log.d(TAG, "stop() — listeners removed")
    }

    private fun listenGlobalPolicy() {
        FirebaseFirestore.getInstance().document("skieConfig/globalPolicy")
            .addSnapshotListener { snap, error ->
                if (error != null) { Log.e(TAG, "globalPolicy error: ${error.message}"); return@addSnapshotListener }
                if (snap == null || !snap.exists()) { Log.w(TAG, "globalPolicy not found"); return@addSnapshotListener }
                listenEffectiveApps()
                listenPolicy()
            }
    }

    private fun listenEffectiveApps() {
        val r = rtdb.getReference("devices/$deviceId/effectiveApps")
        r.keepSynced(true)
        effectiveAppsListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                try {
                    if (!snapshot.exists() || snapshot.childrenCount == 0L) {
                        Log.d(TAG, "effectiveApps: null/empty — showing all installed apps")
                        effectivePackages = null
                        allowedPackages = null
                    } else {
                        val allPkgs = mutableSetOf(context.packageName)
                        val enabledPkgs = mutableSetOf(context.packageName)
                        snapshot.children.forEach { child ->
                            val pkg = child.child("packageName").getValue(String::class.java) ?: return@forEach
                            allPkgs.add(pkg)
                            // allowed field: true = visible on launcher, false = parent toggled off
                            val allowed = child.child("allowed").getValue(Boolean::class.java) ?: true
                            if (allowed) enabledPkgs.add(pkg)
                        }
                        Log.d(TAG, "effectiveApps: ${allPkgs.size} total, ${enabledPkgs.size} allowed")
                        effectivePackages = allPkgs
                        allowedPackages = enabledPkgs
                    }
                    applyAppSuspension()
                } catch (e: Exception) {
                    Log.e(TAG, "effectiveApps onDataChange: ${e.message}")
                }
            }
            override fun onCancelled(error: DatabaseError) { Log.e(TAG, "effectiveApps cancelled: ${error.message}") }
        }
        r.addValueEventListener(effectiveAppsListener!!)
    }

    private fun applyAppSuspension() {
        if (!isDeviceOwner()) { Log.w(TAG, "applyAppSuspension: not device owner"); return }
        try {
            val pm = context.packageManager
            val installed = try {
                pm.getInstalledApplications(PackageManager.GET_META_DATA)
                    .filter { (it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0 }
                    .map { it.packageName }
            } catch (e: Exception) {
                Log.e(TAG, "applyAppSuspension getInstalledApplications: ${e.message}")
                return
            }

            // Use allowedPackages if set (respects parent toggle), otherwise effectivePackages
            val resolvedAllowed: Set<String>? = allowedPackages ?: effectivePackages

            if (resolvedAllowed != null) {
                val allowed = resolvedAllowed + context.packageName + listOfNotNull(activeFocusApp)
                val toSuspend   = installed.filter { it !in allowed }.toTypedArray()
                val toUnsuspend = installed.filter { it in allowed  }.toTypedArray()
                Log.d(TAG, "applyAppSuspension: suspending ${toSuspend.size}, unsuspending ${toUnsuspend.size} schoolMode=$_schoolModeEnabled")
                try {
                    if (toSuspend.isNotEmpty())   dpm.setPackagesSuspended(admin, toSuspend, true)
                    if (toUnsuspend.isNotEmpty()) dpm.setPackagesSuspended(admin, toUnsuspend, false)
                } catch (e: Exception) { Log.e(TAG, "setPackagesSuspended: ${e.message}") }
            } else {
                val toUnsuspend = installed.toTypedArray()
                Log.d(TAG, "applyAppSuspension: no effectiveApps — unsuspending all ${toUnsuspend.size} apps")
                try {
                    if (toUnsuspend.isNotEmpty()) dpm.setPackagesSuspended(admin, toUnsuspend, false)
                } catch (e: Exception) { Log.e(TAG, "setPackagesSuspended: ${e.message}") }
            }

            // Always re-suspend Play Store
            suspendPlayStore()
        } catch (e: Exception) {
            Log.e(TAG, "applyAppSuspension outer: ${e.message}")
        }
    }

    private fun listenPolicy() {
        val r = rtdb.getReference("devices/$deviceId/policy")
        r.keepSynced(true)
        policyListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) return
                try {
                    val cameraDisabled    = snapshot.child("cameraDisabled").getValue(Boolean::class.java) ?: false
                    val schoolModeEnabled = snapshot.child("schoolModeEnabled").getValue(Boolean::class.java) ?: false
                    val screenLockEnabled = snapshot.child("screenLockEnabled").getValue(Boolean::class.java) ?: false
                    val focusMode         = snapshot.child("focusMode").getValue(String::class.java) ?: "none"
                    val focusApp          = snapshot.child("focusApp").getValue(String::class.java)
                    Log.d(TAG, "policy: camera=$cameraDisabled school=$schoolModeEnabled lock=$screenLockEnabled focus=$focusMode")
                    SkiePolicyCache.save(context, snapshot)
                    setCameraDisabled(cameraDisabled)
                    _schoolModeEnabled = schoolModeEnabled
                    setSchoolMode(schoolModeEnabled)
                    if (screenLockEnabled) lockScreen()
                    applyFocusMode(focusMode, focusApp)
                    try { SkieInstalledAppsManager(context).sync() } catch (e: Exception) { Log.e(TAG, "sync: ${e.message}") }
                    SkiePolicyState.notifyUpdated()
                } catch (e: Exception) {
                    Log.e(TAG, "listenPolicy onDataChange: ${e.message}")
                }
            }
            override fun onCancelled(error: DatabaseError) { Log.e(TAG, "policy cancelled: ${error.message}") }
        }
        r.addValueEventListener(policyListener!!)
    }

    fun applyFocusMode(focusMode: String, focusApp: String?) {
        if (!isDeviceOwner()) return
        try {
            val launcher = context.packageName
            Log.d(TAG, "applyFocusMode: mode=$focusMode app=$focusApp")
            when (focusMode) {
                "none" -> {
                    activeFocusApp = null
                    try { dpm.setLockTaskPackages(admin, arrayOf(launcher)) } catch (e: Exception) { Log.e(TAG, "focus none setLockTask: ${e.message}") }
                    applyAppSuspension()
                    sendFocusBroadcast("none", null)
                }
                "focus_time" -> {
                    activeFocusApp = focusApp
                    applyAppSuspension()
                    val pkgs = if (!focusApp.isNullOrEmpty()) arrayOf(launcher, focusApp) else arrayOf(launcher)
                    try { dpm.setLockTaskPackages(admin, pkgs) } catch (e: Exception) { Log.e(TAG, "focus_time setLockTask: ${e.message}") }
                    sendFocusBroadcast("focus_time", focusApp)
                }
                "deep_focus" -> {
                    activeFocusApp = focusApp
                    applyAppSuspension()
                    val pkgs = if (!focusApp.isNullOrEmpty()) arrayOf(launcher, focusApp) else arrayOf(launcher)
                    try { dpm.setLockTaskPackages(admin, pkgs) } catch (e: Exception) { Log.e(TAG, "deep_focus setLockTask: ${e.message}") }
                    sendFocusBroadcast("deep_focus", focusApp)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "applyFocusMode: ${e.message}")
        }
    }

    private fun sendFocusBroadcast(mode: String, focusApp: String?) {
        try {
            val intent = Intent(SkieFocusConstants.ACTION_FOCUS_CHANGED).apply {
                setPackage(context.packageName)
                putExtra(SkieFocusConstants.EXTRA_FOCUS_MODE, mode)
                if (!focusApp.isNullOrEmpty()) putExtra(SkieFocusConstants.EXTRA_FOCUS_APP, focusApp)
            }
            context.sendBroadcast(intent)
            Log.d(TAG, "sendFocusBroadcast: mode=$mode app=$focusApp")
        } catch (e: Exception) {
            Log.e(TAG, "sendFocusBroadcast: ${e.message}")
        }
    }

    private fun clearStaleSuspensions() {
        if (!isDeviceOwner()) return
        try {
            val all = context.packageManager
                .getInstalledApplications(android.content.pm.PackageManager.GET_META_DATA)
                .filter { (it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0 }
                .map { it.packageName }.toTypedArray()
            if (all.isNotEmpty()) dpm.setPackagesSuspended(admin, all, false)
            Log.d(TAG, "clearStaleSuspensions: unsuspended ${all.size} apps")
        } catch (e: Exception) { Log.e(TAG, "clearStaleSuspensions: ${e.message}") }
    }

    private fun hardenDevice() {
        if (!isDeviceOwner()) return
        try {
            dpm.setUninstallBlocked(admin, context.packageName, true)
            dpm.addUserRestriction(admin, UserManager.DISALLOW_FACTORY_RESET)
            dpm.addUserRestriction(admin, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
            dpm.addUserRestriction(admin, UserManager.DISALLOW_MODIFY_ACCOUNTS)
            dpm.addUserRestriction(admin, UserManager.DISALLOW_USB_FILE_TRANSFER)
            dpm.addUserRestriction(admin, UserManager.DISALLOW_SAFE_BOOT)
            Log.d(TAG, "hardenDevice: done")
        } catch (e: Exception) { Log.e(TAG, "hardenDevice: ${e.message}") }
    }

    private fun grantRequiredPermissions() {
        if (!isDeviceOwner()) return
        listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_PHONE_STATE,
        ).forEach {
            try {
                dpm.setPermissionGrantState(admin, context.packageName, it, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
            } catch (e: Exception) { Log.e(TAG, "grant $it: ${e.message}") }
        }
    }

    fun suspendPlayStore() {
        try {
            if (isDeviceOwner()) {
                dpm.setPackagesSuspended(admin, arrayOf(PLAY_STORE_PKG), true)
                Log.d(TAG, "suspendPlayStore: done")
            }
        } catch (e: Exception) { Log.e(TAG, "suspendPlayStore: ${e.message}") }
    }

    fun unsuspendPlayStoreTemporarily(durationMs: Long = 120_000L, onDone: (() -> Unit)? = null) {
        if (!isDeviceOwner()) return
        try {
            dpm.setPackagesSuspended(admin, arrayOf(PLAY_STORE_PKG), false)
            Log.d(TAG, "unsuspendPlayStoreTemporarily: Play Store open for ${durationMs}ms")

            val tunnelEndMs = System.currentTimeMillis() + durationMs

            // Store expiry locally — survives offline
            context.getSharedPreferences(
                com.skiephone.app.admin.PlayStoreTunnelReceiver.PREFS_NAME,
                android.content.Context.MODE_PRIVATE
            ).edit().putLong(
                com.skiephone.app.admin.PlayStoreTunnelReceiver.PREF_TUNNEL_END_MS, tunnelEndMs
            ).apply()

            // AlarmManager fires even if device goes offline
            val alarmManager = context.getSystemService(android.content.Context.ALARM_SERVICE) as android.app.AlarmManager
            val intent = android.content.Intent(context, com.skiephone.app.admin.PlayStoreTunnelReceiver::class.java).apply {
                action = com.skiephone.app.admin.PlayStoreTunnelReceiver.ACTION_TUNNEL_EXPIRED
            }
            val pendingIntent = android.app.PendingIntent.getBroadcast(
                context, 0, intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE
            )
            alarmManager.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, tunnelEndMs, pendingIntent)
            onDone?.invoke()
            Log.d(TAG, "unsuspendPlayStoreTemporarily: alarm set for ${durationMs}ms")
        } catch (e: Exception) {
            Log.e(TAG, "unsuspendPlayStoreTemporarily: ${e.message}")
        }
    }

    fun setCameraDisabled(disabled: Boolean) {
        if (!isDeviceOwner()) return
        try { dpm.setCameraDisabled(admin, disabled); Log.d(TAG, "camera disabled: $disabled") }
        catch (e: Exception) { Log.e(TAG, "setCameraDisabled: ${e.message}") }
    }

    fun lockScreen() {
        if (!isDeviceOwner()) return
        try { dpm.lockNow() } catch (e: Exception) { Log.e(TAG, "lockScreen: ${e.message}") }
    }

    fun setSchoolMode(enabled: Boolean) {
        if (!isDeviceOwner()) return
        try {
            dpm.setKeyguardDisabledFeatures(admin,
                if (enabled) DevicePolicyManager.KEYGUARD_DISABLE_FEATURES_ALL
                else DevicePolicyManager.KEYGUARD_DISABLE_FEATURES_NONE)
        } catch (e: Exception) { Log.e(TAG, "setSchoolMode: ${e.message}") }
    }

    // ── Methods used by SkieCommandManager ───────────────────────────────────

    fun deharden() {
        if (!isDeviceOwner()) return
        try {
            listOf(
                UserManager.DISALLOW_FACTORY_RESET,
                UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES,
                UserManager.DISALLOW_MODIFY_ACCOUNTS,
                UserManager.DISALLOW_USB_FILE_TRANSFER,
                UserManager.DISALLOW_SAFE_BOOT,
                UserManager.DISALLOW_DEBUGGING_FEATURES,
                UserManager.DISALLOW_AIRPLANE_MODE,
                UserManager.DISALLOW_ADD_USER,
                UserManager.DISALLOW_USER_SWITCH,
                UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS,
            ).forEach { try { dpm.clearUserRestriction(admin, it) } catch (e: Exception) { Log.e(TAG, "clearRestriction $it: ${e.message}") } }
            dpm.setScreenCaptureDisabled(admin, false)
            dpm.setUninstallBlocked(admin, context.packageName, false)
            clearStaleSuspensions()
            Log.d(TAG, "deharden: complete")
        } catch (e: Exception) { Log.e(TAG, "deharden: ${e.message}") }
    }

    fun applyHardeningStep(stepId: String) {
        if (!isDeviceOwner()) return
        try {
            when (stepId) {
                "disallow_factory_reset"       -> dpm.addUserRestriction(admin, UserManager.DISALLOW_FACTORY_RESET)
                "disallow_safe_boot"           -> dpm.addUserRestriction(admin, UserManager.DISALLOW_SAFE_BOOT)
                "disallow_usb_file_transfer"   -> dpm.addUserRestriction(admin, UserManager.DISALLOW_USB_FILE_TRANSFER)
                "disallow_modify_accounts"     -> dpm.addUserRestriction(admin, UserManager.DISALLOW_MODIFY_ACCOUNTS)
                "disallow_install_unknown_sources" -> dpm.addUserRestriction(admin, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
                "disallow_debugging_features"  -> dpm.addUserRestriction(admin, UserManager.DISALLOW_DEBUGGING_FEATURES)
                "disallow_airplane_mode"       -> dpm.addUserRestriction(admin, UserManager.DISALLOW_AIRPLANE_MODE)
                "disallow_add_user"            -> dpm.addUserRestriction(admin, UserManager.DISALLOW_ADD_USER)
                "disallow_user_switch"         -> dpm.addUserRestriction(admin, UserManager.DISALLOW_USER_SWITCH)
                "disallow_config_mobile_networks" -> dpm.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS)
                "screen_capture_disabled"      -> dpm.setScreenCaptureDisabled(admin, true)
                "suspend_play_store"           -> suspendPlayStore()
                "block_uninstall_skie"         -> dpm.setUninstallBlocked(admin, context.packageName, true)
                else -> Log.w(TAG, "applyHardeningStep: unknown stepId=$stepId")
            }
            Log.d(TAG, "applyHardeningStep: $stepId applied")
        } catch (e: Exception) { Log.e(TAG, "applyHardeningStep $stepId: ${e.message}") }
    }

    fun setPowerOffDisabled(disabled: Boolean) {
        try {
            // API 35+ only — use string literal to avoid compile error on lower API
            val pm = context.packageManager
            if (pm.hasSystemFeature("no_power_off")) {
                dpm.addUserRestriction(admin, "no_power_off")
            }
            Log.d(TAG, "setPowerOffDisabled: $disabled (best effort)")
        } catch (e: Exception) { Log.e(TAG, "setPowerOffDisabled: ${e.message}") }
    }

    fun applyFocusNetworkLock(active: Boolean) {
        try {
            val settingsPkg = "com.android.settings"
            dpm.setPackagesSuspended(admin, arrayOf(settingsPkg), active)
            Log.d(TAG, "applyFocusNetworkLock: active=$active — settings suspended=$active")
        } catch (e: Exception) { Log.e(TAG, "applyFocusNetworkLock: ${e.message}") }
    }

    fun repinLauncherHome() {
        try {
            if (!isDeviceOwner()) return
            val filter = android.content.IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addCategory(Intent.CATEGORY_DEFAULT)
            }
            val launcher = ComponentName(context.packageName, "${context.packageName}.launcher.LauncherActivity")
            dpm.clearPackagePersistentPreferredActivities(admin, context.packageName)
            dpm.addPersistentPreferredActivity(admin, filter, launcher)
            Log.d(TAG, "repinLauncherHome: done")
        } catch (e: Exception) { Log.e(TAG, "repinLauncherHome: ${e.message}") }
    }

    fun isDeviceOwner() = try { dpm.isDeviceOwnerApp(context.packageName) } catch (e: Exception) { false }

    fun requestDeviceAdmin() {
        try {
            context.startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Skie requires device admin to manage policies.")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) { Log.e(TAG, "requestDeviceAdmin: ${e.message}") }
    }

    companion object {
        private const val TAG = "SkiePolicyEngine"
        const val PLAY_STORE_PKG = "com.android.vending"
        const val PLAY_SERVICES_PKG = "com.google.android.gms"
        const val STEP_DISALLOW_FACTORY_RESET        = "disallow_factory_reset"
        const val STEP_DISALLOW_SAFE_BOOT            = "disallow_safe_boot"
        const val STEP_DISALLOW_USB_FILE_TRANSFER    = "disallow_usb_file_transfer"
        const val STEP_DISALLOW_MODIFY_ACCOUNTS      = "disallow_modify_accounts"
        const val STEP_DISALLOW_INSTALL_UNKNOWN      = "disallow_install_unknown_sources"
        const val STEP_DISALLOW_DEBUGGING            = "disallow_debugging_features"
        const val STEP_DISALLOW_AIRPLANE_MODE        = "disallow_airplane_mode"
        const val STEP_DISALLOW_ADD_USER             = "disallow_add_user"
        const val STEP_DISALLOW_USER_SWITCH          = "disallow_user_switch"
        const val STEP_DISALLOW_CONFIG_MOBILE        = "disallow_config_mobile_networks"
        const val STEP_SCREEN_CAPTURE_DISABLED       = "screen_capture_disabled"
        const val STEP_SUSPEND_PLAY_STORE            = "suspend_play_store"
        const val STEP_BLOCK_UNINSTALL               = "block_uninstall_skie"
    }
}