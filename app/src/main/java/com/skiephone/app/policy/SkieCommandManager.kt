package com.skiephone.app.policy

import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.skiephone.app.admin.SkieDeviceAdminReceiver
import com.skiephone.app.admin.UpdateReceiver

class SkieCommandManager(private val context: Context) {

    companion object {
        const val TAG = "SkieCommandManager"
        private const val PREFS = "skie_command"
        private const val KEY_LAST_CMD_AT = "last_cmd_issued_at"
        // Commands older than this are stale — ignore and clear
        private const val MAX_AGE_MS = 30_000L
        // Destructive commands have a tighter staleness window
        private const val MAX_AGE_DESTRUCTIVE_MS = 10_000L
        private val DESTRUCTIVE_COMMANDS = setOf(
            "REMOVE_DEVICE_OWNER", "FACTORY_RESET", "DEHARDEN", "RESTART_PHONE"
        )
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

    init {
        // Restore last executed issuedAt from SharedPreferences so dedup
        // survives process restart — prevents stale commands re-executing on reboot.
        lastExecutedIssuedAt = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_CMD_AT, -1L)
        Log.d(TAG, "Restored lastExecutedIssuedAt=$lastExecutedIssuedAt")
    }

    private fun persistLastExecutedIssuedAt(issuedAt: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(KEY_LAST_CMD_AT, issuedAt).apply()
    }

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

                val issuedAt = snapshot.child("issuedAt")
                    .getValue(Long::class.java) ?: 0L

                // FIX 2: Dedup using persisted lastExecutedIssuedAt — survives restarts
                if (issuedAt != 0L && issuedAt == lastExecutedIssuedAt) {
                    Log.d(TAG, "Skipping duplicate command (issuedAt=$issuedAt): $action")
                    return
                }

                // FIX 1: Staleness guard — ignore commands issued before this session
                // Destructive commands (REMOVE_DEVICE_OWNER, FACTORY_RESET etc) have
                // tighter window to prevent re-execution after re-provision.
                if (issuedAt > 0L) {
                    val age = System.currentTimeMillis() - issuedAt
                    val maxAge = if (action in DESTRUCTIVE_COMMANDS) MAX_AGE_DESTRUCTIVE_MS else MAX_AGE_MS
                    if (age > maxAge) {
                        Log.w(TAG, "Stale command ignored: $action age=${age}ms (max=${maxAge}ms) — clearing node")
                        database.getReference("devices/$deviceId/command").removeValue()
                        return
                    }
                }

                Log.d(TAG, "Command received: $action (issuedAt=$issuedAt)")
                lastExecutedIssuedAt = issuedAt
                persistLastExecutedIssuedAt(issuedAt)

                when (action) {
                    "REMOVE_DEVICE_OWNER"       -> removeDeviceOwner()
                    "FACTORY_RESET"             -> factoryReset()
                    "DEHARDEN"                  -> deharden()
                    "INSTALL_APP"               -> installApp(snapshot)
                    "UNINSTALL_APP"             -> uninstallApp(snapshot)
                    "APPLY_HARDENING_STEP"      -> applyHardeningStep(snapshot)
                    "APPLY_ALL_HARDENING_STEPS" -> applyAllHardeningSteps()
                    "RESTART_SERVICE"           -> restartService()
                    "DISTRACTION_TIMER"         -> handleDistractionTimer(snapshot)
                    "CANCEL_DISTRACTION_TIMER"  -> handleCancelDistractionTimer(snapshot)
                    "REMOVE_HARDENING_STEP"     -> removeHardeningStep(snapshot)
                    "CHECK_COMPLIANCE"          -> checkCompliance()
                    "POWER_OFF_DISABLE"         -> setPowerOffDisabled(snapshot)
                    "SUSPEND_APP"               -> suspendApp(snapshot, true)
                    "UNSUSPEND_APP"             -> suspendApp(snapshot, false)
                    "SYNC_POLICY"               -> syncPolicy()
                    "RESTART_PHONE"             -> restartPhone()
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
            persistLastExecutedIssuedAt(-1L)
        }
    }

    private fun factoryReset() {
        try {
            Log.d(TAG, "Factory reset initiated")
            dpm.wipeData(0)
        } catch (e: Exception) {
            Log.e(TAG, "Factory reset failed: ${e.message}")
            lastExecutedIssuedAt = -1L
            persistLastExecutedIssuedAt(-1L)
        }
    }

    /**
     * DEHARDEN command from dashboard — undoes everything: restrictions removed,
     * verified cleared, and every app on the device unsuspended/unhidden.
     * Shares the exact same logic as the in-app Offboarding screen's steps 1-3
     * (SkieDehardenRunner / SkieDehardenVerifier / SkieRestoreAppsRunner) so
     * dashboard-triggered and on-device-triggered deharden never diverge.
     * Headless — no progress UI, no RTDB result writeback, just runs to completion.
     */
    private fun deharden() {
        try {
            Log.d(TAG, "Deharden initiated (dashboard)")
            val engine = SkiePolicyEngine(context)
            com.skiephone.app.compliance.SkieDehardenRunner.runAll(
                context, engine,
                onProgress = { _, _ -> /* headless — no UI to update */ },
                onComplete = { _ ->
                    com.skiephone.app.compliance.SkieDehardenVerifier.runAll(
                        context,
                        onProgress = { _, _ -> /* headless */ },
                        onComplete = { _ ->
                            com.skiephone.app.compliance.SkieRestoreAppsRunner.runAll(
                                context,
                                onProgress = { _, _, _ -> /* headless */ },
                                onComplete = { _ ->
                                    // Mark device as no longer managed — critical for re-provisioning.
                                    // SkieDehardenRunner also sets this, but we set it here too since
                                    // the dashboard path is the one most likely to be called without
                                    // the DPC app's own offboarding UI ever running.
                                    database.getReference("devices/$deviceId/isDeviceManaged")
                                        .setValue(false)
                                    database.getReference("devices/$deviceId/command").removeValue()
                                    database.getReference("devices/$deviceId/status").setValue("dehardened")
                                    Log.d(TAG, "Deharden complete (dashboard)")
                                }
                            )
                        }
                    )
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Deharden failed: ${e.message}")
            lastExecutedIssuedAt = -1L
            persistLastExecutedIssuedAt(-1L)
        }
    }

    /**
     * INSTALL_APP — smart install:
     * 1. Already installed → just unsuspend (no Play Store needed)
     * 2. Previously installed (system cached) → DPM.installExistingPackage silent install
     * 3. Brand new app → open Play Store as last resort only
     * Play Store is NEVER opened for reinstalls.
     */
    private fun installApp(snapshot: DataSnapshot) {
        val packageName = snapshot.child("packageName").getValue(String::class.java) ?: run {
            Log.w(TAG, "installApp: no packageName in command")
            database.getReference("devices/$deviceId/command").removeValue()
            return
        }

        Log.d(TAG, "installApp: $packageName")

        // Case 1 — already installed
        // Check effectiveApps allowed field before unsuspending.
        // If parent toggled it OFF (allowed=false), do NOT unsuspend — keep it suspended.
        val isInstalled = try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: Exception) { false }

        if (isInstalled) {
            val effectiveRef = database.getReference("devices/$deviceId/effectiveApps")
            effectiveRef.get().addOnSuccessListener { snap ->
                var allowedByPolicy = true // default: allow if not found in effectiveApps
                snap.children.forEach { child ->
                    val pkg = child.child("packageName").getValue(String::class.java)
                    if (pkg == packageName) {
                        allowedByPolicy = child.child("allowed").getValue(Boolean::class.java) ?: true
                    }
                }
                try {
                    if (dpm.isDeviceOwnerApp(context.packageName)) {
                        if (allowedByPolicy) {
                            dpm.setPackagesSuspended(componentName, arrayOf(packageName), false)
                            Log.d(TAG, "installApp: $packageName already installed — unsuspended (allowed=true)")
                        } else {
                            Log.d(TAG, "installApp: $packageName already installed but allowed=false — keeping suspended")
                        }
                    }
                    writeInstallResult(packageName, true, if (allowedByPolicy) "unsuspended" else "kept_suspended")
                    // Sync immediately so installedApps reflects current suspended state
                    try { com.skiephone.app.services.SkieInstalledAppsManager(context).sync() }
                    catch (e: Exception) { Log.e(TAG, "installApp sync after unsuspend: ${e.message}") }
                } catch (e: Exception) {
                    writeInstallResult(packageName, false, e.message)
                }
                database.getReference("devices/$deviceId/command").removeValue()
            }.addOnFailureListener {
                // Firestore unavailable — default to unsuspend to not block legitimate installs
                try {
                    if (dpm.isDeviceOwnerApp(context.packageName)) {
                        dpm.setPackagesSuspended(componentName, arrayOf(packageName), false)
                    }
                } catch (e: Exception) { Log.e(TAG, "installApp fallback unsuspend: ${e.message}") }
                database.getReference("devices/$deviceId/command").removeValue()
            }
            return
        }

        // Case 2 — try silent install via DPM (works if previously installed AND cached)
        // NOTE: installExistingPackage() does NOT throw if app is not cached — it silently does nothing.
        // So we must verify actual installation after calling it.
        val silentOk = try {
            dpm.installExistingPackage(componentName, packageName)
            // Wait briefly then verify if it actually installed
            android.os.SystemClock.sleep(2000)
            val actuallyInstalled = try {
                context.packageManager.getPackageInfo(packageName, 0)
                true
            } catch (e2: Exception) { false }
            if (actuallyInstalled) {
                writeInstallResult(packageName, true, "silent_reinstall")
                Log.d(TAG, "installApp: $packageName silent reinstall verified installed")
                true
            } else {
                Log.d(TAG, "installApp: installExistingPackage returned but app not found — falling through to Play Store")
                false
            }
        } catch (e: Exception) {
            Log.d(TAG, "installApp: silent reinstall not available — ${e.message}")
            false
        }

        if (silentOk) {
            database.getReference("devices/$deviceId/command").removeValue()
            return
        }

        // Case 3 — brand new app, open Play Store as last resort.
        // Play Store is now an ordinary app controlled via App Management — if it's
        // suspended, this launch will simply fail silently, which is correct/expected.
        Log.d(TAG, "installApp: $packageName is new — opening Play Store")
        try {
            val installIntent = Intent(Intent.ACTION_VIEW)
            installIntent.data = android.net.Uri.parse("market://details?id=$packageName")
            installIntent.setPackage("com.android.vending")
            installIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(installIntent)
            writeInstallResult(packageName, true, "play_store_opened")
            Log.d(TAG, "installApp: Play Store opened for $packageName")
        } catch (e: Exception) {
            Log.e(TAG, "installApp: Play Store launch failed — ${e.message}")
            writeInstallResult(packageName, false, e.message)
        }

        database.getReference("devices/$deviceId/command").removeValue()
    }

    /**
     * UNINSTALL_APP — silently uninstall via PackageInstaller.
     * Command payload: { action: "UNINSTALL_APP", packageName: "com.whatsapp" }
     */
    private fun uninstallApp(snapshot: DataSnapshot) {
        val packageName = snapshot.child("packageName").getValue(String::class.java) ?: run {
            Log.w(TAG, "uninstallApp: no packageName in command")
            database.getReference("devices/$deviceId/command").removeValue()
            return
        }

        Log.d(TAG, "uninstallApp: $packageName")

        val pm = context.packageManager
        val isSystemApp = try {
            val appInfo = pm.getApplicationInfo(packageName, 0)
            (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
        } catch (e: Exception) { false }

        if (isSystemApp) {
            // System apps cannot be uninstalled — disable them instead.
            // Guard against hiding critical system packages that would break the device.
            if (packageName in SkiePolicyEngine.SYSTEM_PROTECTED_PACKAGES_PUBLIC) {
                Log.w(TAG, "uninstallApp: BLOCKED — $packageName is a protected system package")
                writeUninstallResult(packageName, false, "protected_system_package")
                return
            }
            try {
                dpm.setApplicationHidden(componentName, packageName, true)
                Log.d(TAG, "uninstallApp: system app hidden (disabled): $packageName")
                writeUninstallResult(packageName, true, "system_app_disabled")
            } catch (e: Exception) {
                Log.e(TAG, "uninstallApp: failed to disable system app — ${e.message}")
                writeUninstallResult(packageName, false, e.message)
            }
        } else {
            try {
                val packageInstaller = pm.packageInstaller
                val intent = Intent(context, UpdateReceiver::class.java).apply {
                    action = "com.skiephone.app.UNINSTALL_COMPLETE"
                    putExtra("packageName", packageName)
                }
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    packageName.hashCode(),
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )
                packageInstaller.uninstall(packageName, pendingIntent.intentSender)
                Log.d(TAG, "uninstallApp: uninstall initiated for $packageName")
                writeUninstallResult(packageName, true, null)
            } catch (e: Exception) {
                Log.e(TAG, "uninstallApp: failed — ${e.message}")
                writeUninstallResult(packageName, false, e.message)
            }
        }

        database.getReference("devices/$deviceId/command").removeValue()
    }

    /**
     * APPLY_HARDENING_STEP — apply a single hardening step by stepId.
     * Command payload: { action: "APPLY_HARDENING_STEP", stepId: "disallow_factory_reset" }
     */
    private fun applyHardeningStep(snapshot: DataSnapshot) {
        val stepId = snapshot.child("stepId").getValue(String::class.java) ?: run {
            Log.w(TAG, "applyHardeningStep: no stepId in command")
            database.getReference("devices/$deviceId/command").removeValue()
            return
        }
        try {
            Log.d(TAG, "applyHardeningStep: $stepId")
            val engine = SkiePolicyEngine(context)
            if (stepId == "dns_filtering") {
                // DNS step is async (network check on background thread)
                // Pass callback to write hardenLog only after actual success
                engine.applyDnsFiltering(true) {
                    database.getReference("devices/$deviceId/hardenLog/dns_filtering")
                        .setValue(mapOf("success" to true, "ts" to System.currentTimeMillis()))
                    Log.d(TAG, "applyHardeningStep: dns_filtering hardenLog written ✓")
                }
            } else {
                engine.applyHardeningStep(stepId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "applyHardeningStep: $stepId failed — ${e.message}")
        }
        database.getReference("devices/$deviceId/command").removeValue()
    }


    /**
     * APPLY_ALL_HARDENING_STEPS — apply every hardening step in one command.
     * Fixes the race condition where rapid sequential APPLY_HARDENING_STEP
     * commands overwrite each other in the single RTDB command node.
     * Dashboard sends one command, device handles all steps internally.
     */
    private fun applyAllHardeningSteps() {
        val engine = SkiePolicyEngine(context)
        val allSteps = listOf(
            "disallow_factory_reset",
            "disallow_safe_boot",
            "disallow_usb_file_transfer",
            "disallow_modify_accounts",
            "disallow_install_unknown_sources",
            "disallow_debugging_features",
            "disallow_airplane_mode",
            "disallow_add_user",
            "disallow_user_switch",
            "disallow_config_mobile_networks",
            "screen_capture_disabled",
            "block_uninstall_skie"
        )
        val hardenLogRef = database.getReference("devices/$deviceId/hardenLog")
        allSteps.forEach { stepId ->
            try {
                engine.applyHardeningStep(stepId)
                hardenLogRef.child(stepId).setValue(
                    mapOf("success" to true, "ts" to System.currentTimeMillis())
                )
                Log.d(TAG, "applyAllHardeningSteps: $stepId ✓")
            } catch (e: Exception) {
                Log.e(TAG, "applyAllHardeningSteps: $stepId failed — ${e.message}")
            }
        }
        // DNS is async — handle separately
        engine.applyDnsFiltering(true) {
            hardenLogRef.child("dns_filtering").setValue(
                mapOf("success" to true, "ts" to System.currentTimeMillis())
            )
            Log.d(TAG, "applyAllHardeningSteps: dns_filtering ✓")
        }
        database.getReference("devices/$deviceId/command").removeValue()
        Log.d(TAG, "applyAllHardeningSteps: done")
    }

    /**
     * RESTART_SERVICE — stops SkiePolicyService cleanly.
     * START_STICKY + onTaskRemoved alarm brings it back in ~1s.
     * Restarts: SkiePolicyEngine, SkieCommandManager, SkieBatteryManager,
     *           SkieInstalledAppsManager, SkieLocationManager, SkieSOSManager,
     *           SkieTravelManager, SkieUpdateManager — everything owned by the service.
     */
    private fun restartService() {
        Log.d(TAG, "restartService: stopping SkiePolicyService for restart")
        database.getReference("devices/$deviceId/command").removeValue()
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            try {
                val intent = android.content.Intent(context, com.skiephone.app.services.SkiePolicyService::class.java)
                context.stopService(intent)
                Log.d(TAG, "restartService: stopService called — START_STICKY will restart")
            } catch (e: Exception) {
                Log.e(TAG, "restartService: ${e.message}")
            }
        }, 300)
    }

    private fun removeHardeningStep(snapshot: DataSnapshot) {
        val stepId = snapshot.child("stepId").getValue(String::class.java) ?: run {
            Log.w(TAG, "removeHardeningStep: no stepId in command")
            database.getReference("devices/$deviceId/command").removeValue()
            return
        }
        try {
            Log.d(TAG, "removeHardeningStep: $stepId")
            val engine = SkiePolicyEngine(context)
            engine.removeHardeningStep(stepId)
            // Clear hardenLog entry so dashboard reflects removed state
            database.getReference("devices/$deviceId/hardenLog/$stepId").removeValue()
        } catch (e: Exception) {
            Log.e(TAG, "removeHardeningStep: $stepId failed — ${e.message}")
        }
        database.getReference("devices/$deviceId/command").removeValue()
    }

    /**
     * POWER_OFF_DISABLE — optional toggle, Owner/Parent dashboard only.
     * Command payload: { action: "POWER_OFF_DISABLE", disabled: true }
     */
    private fun setPowerOffDisabled(snapshot: DataSnapshot) {
        val disabled = snapshot.child("disabled").getValue(Boolean::class.java) ?: true
        try {
            val engine = SkiePolicyEngine(context)
            engine.setPowerOffDisabled(disabled)
            Log.d(TAG, "POWER_OFF_DISABLE: disabled=$disabled")
        } catch (e: Exception) {
            Log.e(TAG, "POWER_OFF_DISABLE failed: ${e.message}")
        }
        database.getReference("devices/$deviceId/command").removeValue()
    }

    /**
     * CHECK_COMPLIANCE — re-runs all DPM checks and rewrites hardenLog with current state.
     * Triggered by "Refresh Compliance" button in Owner Dashboard.
     */
    private fun checkCompliance() {
        if (!dpm.isDeviceOwnerApp(context.packageName)) {
            database.getReference("devices/$deviceId/command").removeValue()
            return
        }
        Log.d(TAG, "checkCompliance: running")
        val engine = SkiePolicyEngine(context)
        val steps = mapOf(
            SkiePolicyEngine.STEP_DISALLOW_FACTORY_RESET     to android.os.UserManager.DISALLOW_FACTORY_RESET,
            SkiePolicyEngine.STEP_DISALLOW_SAFE_BOOT         to android.os.UserManager.DISALLOW_SAFE_BOOT,
            SkiePolicyEngine.STEP_DISALLOW_USB_FILE_TRANSFER to android.os.UserManager.DISALLOW_USB_FILE_TRANSFER,
            SkiePolicyEngine.STEP_DISALLOW_MODIFY_ACCOUNTS   to android.os.UserManager.DISALLOW_MODIFY_ACCOUNTS,
            SkiePolicyEngine.STEP_DISALLOW_INSTALL_UNKNOWN   to android.os.UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES,
            SkiePolicyEngine.STEP_DISALLOW_DEBUGGING         to android.os.UserManager.DISALLOW_DEBUGGING_FEATURES,
            SkiePolicyEngine.STEP_DISALLOW_AIRPLANE_MODE     to android.os.UserManager.DISALLOW_AIRPLANE_MODE,
            SkiePolicyEngine.STEP_DISALLOW_ADD_USER          to android.os.UserManager.DISALLOW_ADD_USER,
            SkiePolicyEngine.STEP_DISALLOW_USER_SWITCH       to android.os.UserManager.DISALLOW_USER_SWITCH,
            SkiePolicyEngine.STEP_DISALLOW_CONFIG_MOBILE     to android.os.UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS,
        )
        val um = context.getSystemService(Context.USER_SERVICE) as android.os.UserManager
        steps.forEach { (stepId, restriction) ->
            val active = um.getUserRestrictions().getBoolean(restriction, false)
            database.getReference("devices/$deviceId/hardenLog/$stepId").setValue(
                mapOf("success" to active, "ts" to System.currentTimeMillis())
            )
        }
        // Check screen capture
        val screenCaptureDisabled = dpm.getScreenCaptureDisabled(componentName)
        database.getReference("devices/$deviceId/hardenLog/${SkiePolicyEngine.STEP_SCREEN_CAPTURE_DISABLED}").setValue(
            mapOf("success" to screenCaptureDisabled, "ts" to System.currentTimeMillis())
        )
        // Check uninstall blocked
        val uninstallBlocked = dpm.isUninstallBlocked(componentName, context.packageName)
        database.getReference("devices/$deviceId/hardenLog/${SkiePolicyEngine.STEP_BLOCK_UNINSTALL}").setValue(
            mapOf("success" to uninstallBlocked, "ts" to System.currentTimeMillis())
        )
        // Check FRP policy
        val frpPolicy = dpm.getFactoryResetProtectionPolicy(componentName)
        val frpActive = frpPolicy?.isFactoryResetProtectionEnabled == true &&
                frpPolicy.factoryResetProtectionAccounts.isNotEmpty()
        database.getReference("devices/$deviceId/hardenLog/${SkiePolicyEngine.STEP_FRP}").setValue(
            mapOf("success" to frpActive, "ts" to System.currentTimeMillis())
        )
        database.getReference("devices/$deviceId/command").removeValue()
        Log.d(TAG, "checkCompliance: done")
    }


    /**
     * SUSPEND_APP / UNSUSPEND_APP — toggle app visibility without uninstalling.
     * Command payload: { action: "SUSPEND_APP", packageName: "com.whatsapp" }
     * Suspend: hides from launcher, app stays installed. Unsuspend: shows on launcher.
     */
    private fun suspendApp(snapshot: DataSnapshot, suspend: Boolean) {
        val packageName = snapshot.child("packageName").getValue(String::class.java) ?: run {
            Log.w(TAG, "suspendApp: no packageName")
            database.getReference("devices/$deviceId/command").removeValue()
            return
        }
        try {
            if (dpm.isDeviceOwnerApp(context.packageName)) {
                val failed = dpm.setPackagesSuspended(componentName, arrayOf(packageName), suspend)
                if (failed.isEmpty()) {
                    Log.d(TAG, "suspendApp: $packageName suspend=$suspend ✓")
                } else {
                    Log.e(TAG, "suspendApp: $packageName failed — package not found or not suspendable")
                }
                // Sync immediately so installedApps suspended field reflects new state
                try { com.skiephone.app.services.SkieInstalledAppsManager(context).sync() }
                catch (e: Exception) { Log.e(TAG, "suspendApp sync: ${e.message}") }
            } else {
                Log.e(TAG, "suspendApp: not device owner — cannot suspend $packageName")
            }
        } catch (e: Exception) {
            Log.e(TAG, "suspendApp: ${e.message}")
        }
        database.getReference("devices/$deviceId/command").removeValue()
    }

    /**
     * SYNC_POLICY — forces immediate sync of installedApps from PackageManager.
     * Triggered by Refresh button on parent dashboard and owner bloatware panel.
     * Device rewrites installedApps with fresh state including suspended field.
     */
    private fun syncPolicy() {
        Log.d(TAG, "syncPolicy: triggering SkieInstalledAppsManager.sync()")
        try {
            com.skiephone.app.services.SkieInstalledAppsManager(context).sync()
        } catch (e: Exception) {
            Log.e(TAG, "syncPolicy: ${e.message}")
        }
        database.getReference("devices/$deviceId/command").removeValue()
    }

    /**
     * RESTART_PHONE — shows a full-screen system overlay countdown (30s) then reboots.
     * Overlay is drawn via SkieRestartService using TYPE_APPLICATION_OVERLAY so it
     * appears over all apps, not just the launcher.
     * Command node is cleared BEFORE starting the service so the stale command
     * guard prevents re-execution after the reboot.
     */
    private fun restartPhone() {
        Log.d(TAG, "RESTART_PHONE: clearing command node then starting restart service")
        // Clear command node FIRST — ensures post-reboot device ignores this command
        // even if SharedPrefs is cleared (e.g. app data wipe before reboot)
        database.getReference("devices/$deviceId/command").removeValue()
        try {
            val intent = android.content.Intent(
                context,
                com.skiephone.app.services.SkieRestartService::class.java
            )
            context.startForegroundService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "RESTART_PHONE: failed to start SkieRestartService: ${e.message}")
        }
    }

    private fun writeInstallResult(packageName: String, success: Boolean, error: String?) {
        val entry = mutableMapOf<String, Any>(
            "packageName" to packageName,
            "success" to success,
            "ts" to System.currentTimeMillis()
        )
        if (error != null) entry["error"] = error
        val safeKey = packageName.replace(".", "_")
        database.getReference("devices/$deviceId/installResults/$safeKey").setValue(entry)
    }

    private fun writeUninstallResult(packageName: String, success: Boolean, error: String?) {
        val entry = mutableMapOf<String, Any>(
            "packageName" to packageName,
            "success" to success,
            "ts" to System.currentTimeMillis()
        )
        if (error != null) entry["error"] = error
        val safeKey = packageName.replace(".", "_")
        database.getReference("devices/$deviceId/uninstallResults/$safeKey").setValue(entry)
    }

    /**
     * DISTRACTION_TIMER — enable a distraction app for a fixed duration.
     * Payload: { packageName: String, durationMs: Long }
     * Saves timer to SharedPrefs + RTDB, schedules WorkManager task, unsuspends app.
     */
    private fun handleDistractionTimer(snapshot: com.google.firebase.database.DataSnapshot) {
        val packageName = snapshot.child("packageName").getValue(String::class.java) ?: run {
            Log.w(TAG, "handleDistractionTimer: no packageName")
            database.getReference("devices/$deviceId/command").removeValue()
            return
        }
        val durationMs = snapshot.child("durationMs").getValue(Long::class.java)
            ?: com.skiephone.app.distraction.DistractionManager.getDefaultDurationMs(context)

        Log.d(TAG, "handleDistractionTimer: $packageName for ${durationMs / 1000}s")
        com.skiephone.app.distraction.DistractionManager.enableTimer(context, packageName, durationMs)
        database.getReference("devices/$deviceId/command").removeValue()
    }

    /**
     * CANCEL_DISTRACTION_TIMER — cancel an active distraction timer early.
     * Payload: { packageName: String }
     * Cancels WorkManager task, suspends app, clears timer from SharedPrefs + RTDB.
     */
    private fun handleCancelDistractionTimer(snapshot: com.google.firebase.database.DataSnapshot) {
        val packageName = snapshot.child("packageName").getValue(String::class.java) ?: run {
            Log.w(TAG, "handleCancelDistractionTimer: no packageName")
            database.getReference("devices/$deviceId/command").removeValue()
            return
        }
        Log.d(TAG, "handleCancelDistractionTimer: $packageName")
        com.skiephone.app.distraction.DistractionManager.cancelTimer(context, packageName)
        database.getReference("devices/$deviceId/command").removeValue()
    }
}