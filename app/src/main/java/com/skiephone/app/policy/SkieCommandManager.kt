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

                val issuedAt = snapshot.child("issuedAt")
                    .getValue(Long::class.java) ?: 0L

                if (issuedAt != 0L && issuedAt == lastExecutedIssuedAt) {
                    Log.d(TAG, "Skipping duplicate command (issuedAt=$issuedAt): $action")
                    return
                }

                Log.d(TAG, "Command received: $action (issuedAt=$issuedAt)")
                lastExecutedIssuedAt = issuedAt

                when (action) {
                    "REMOVE_DEVICE_OWNER"   -> removeDeviceOwner()
                    "FACTORY_RESET"         -> factoryReset()
                    "DEHARDEN"              -> deharden()
                    "TOGGLE_PLAY_STORE"     -> togglePlayStore(snapshot)
                    "INSTALL_APP"           -> installApp(snapshot)
                    "UNINSTALL_APP"         -> uninstallApp(snapshot)
                    "APPLY_HARDENING_STEP"  -> applyHardeningStep(snapshot)
                    "CHECK_COMPLIANCE"      -> checkCompliance()
                    "POWER_OFF_DISABLE"     -> setPowerOffDisabled(snapshot)
                    "SUSPEND_APP"           -> suspendApp(snapshot, true)
                    "UNSUSPEND_APP"         -> suspendApp(snapshot, false)
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

    private fun deharden() {
        try {
            Log.d(TAG, "Deharden initiated")
            val engine = SkiePolicyEngine(context)
            engine.deharden()
            database.getReference("devices/$deviceId/command").removeValue()
            database.getReference("devices/$deviceId/status").setValue("dehardened")
            Log.d(TAG, "Deharden complete")
        } catch (e: Exception) {
            Log.e(TAG, "Deharden failed: ${e.message}")
            lastExecutedIssuedAt = -1L
        }
    }

    /**
     * TOGGLE_PLAY_STORE — open Play Store tunnel for durationMs (default 120s).
     * Command payload: { action: "TOGGLE_PLAY_STORE", durationMs: 120000 }
     */
    private fun togglePlayStore(snapshot: DataSnapshot) {
        val durationMs = snapshot.child("durationMs").getValue(Long::class.java) ?: 120_000L

        // durationMs = 0 means parent toggled off — close tunnel immediately
        if (durationMs == 0L) {
            Log.d(TAG, "togglePlayStore: close command received")
            com.skiephone.app.admin.PlayStoreTunnelReceiver.closeTunnel(context)
            database.getReference("devices/$deviceId/command").removeValue()
            return
        }

        val tunnelEndMs = System.currentTimeMillis() + durationMs
        try {
            Log.d(TAG, "togglePlayStore: opening tunnel for ${durationMs}ms")
            val engine = SkiePolicyEngine(context)

            // Add Play Store to effectiveApps so it appears on launcher
            val effectiveAppsRef = database.getReference("devices/$deviceId/effectiveApps")
            effectiveAppsRef.get().addOnSuccessListener { snapshot2 ->
                val current = mutableListOf<Map<String, Any>>()
                snapshot2.children.forEach { child ->
                    val pkg = child.child("packageName").getValue(String::class.java) ?: return@forEach
                    if (pkg != SkiePolicyEngine.PLAY_STORE_PKG) {
                        current.add(mapOf(
                            "packageName" to pkg,
                            "appName" to (child.child("appName").getValue(String::class.java) ?: pkg),
                            "category" to (child.child("category").getValue(String::class.java) ?: "other")
                        ))
                    }
                }
                current.add(mapOf(
                    "packageName" to SkiePolicyEngine.PLAY_STORE_PKG,
                    "appName" to "Play Store",
                    "category" to "other"
                ))
                effectiveAppsRef.setValue(current)
                Log.d(TAG, "togglePlayStore: Play Store added to effectiveApps")
            }

            engine.unsuspendPlayStoreTemporarily(durationMs)
            database.getReference("devices/$deviceId/playStoreTunnel").setValue(true)
            database.getReference("devices/$deviceId/playStoreTunnelEndsAt").setValue(tunnelEndMs)
            database.getReference("devices/$deviceId/command").removeValue()
        } catch (e: Exception) {
            Log.e(TAG, "togglePlayStore failed: ${e.message}")
            lastExecutedIssuedAt = -1L
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

        // Case 1 — already installed, just unsuspend
        val isInstalled = try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: Exception) { false }

        if (isInstalled) {
            try {
                if (dpm.isDeviceOwnerApp(context.packageName)) {
                    dpm.setPackagesSuspended(componentName, arrayOf(packageName), false)
                }
                writeInstallResult(packageName, true, "unsuspended")
                Log.d(TAG, "installApp: $packageName already installed — unsuspended")
            } catch (e: Exception) {
                writeInstallResult(packageName, false, e.message)
            }
            database.getReference("devices/$deviceId/command").removeValue()
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

        // Case 3 — brand new app, open Play Store as last resort
        Log.d(TAG, "installApp: $packageName is new — opening Play Store")
        val tunnelEndMs = System.currentTimeMillis() + 300_000L
        try {
            val engine = SkiePolicyEngine(context)
            dpm.setPackagesSuspended(componentName, arrayOf(SkiePolicyEngine.PLAY_STORE_PKG), false)
            val installIntent = Intent(Intent.ACTION_VIEW)
            installIntent.data = android.net.Uri.parse("market://details?id=$packageName")
            installIntent.setPackage(SkiePolicyEngine.PLAY_STORE_PKG)
            installIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(installIntent)
            engine.unsuspendPlayStoreTemporarily(300_000L)
            database.getReference("devices/$deviceId/playStoreTunnel").setValue(true)
            database.getReference("devices/$deviceId/playStoreTunnelEndsAt").setValue(tunnelEndMs)
            writeInstallResult(packageName, true, "play_store_opened")
            Log.d(TAG, "installApp: Play Store opened for $packageName")
        } catch (e: Exception) {
            Log.e(TAG, "installApp: Play Store launch failed — ${e.message}")
            writeInstallResult(packageName, false, e.message)
        }

        database.getReference("devices/$deviceId/playStoreTunnel").setValue(true)
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
            // System apps cannot be uninstalled — disable them instead
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
            engine.applyHardeningStep(stepId)
        } catch (e: Exception) {
            Log.e(TAG, "applyHardeningStep: $stepId failed — ${e.message}")
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
                dpm.setPackagesSuspended(componentName, arrayOf(packageName), suspend)
                Log.d(TAG, "suspendApp: $packageName suspend=$suspend")
                updateEffectiveAppStatus(packageName, if (suspend) "suspended" else "installed")
            }
        } catch (e: Exception) {
            Log.e(TAG, "suspendApp: ${e.message}")
        }
        database.getReference("devices/$deviceId/command").removeValue()
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

        // Update effectiveApps status on successful install
        if (success) {
            updateEffectiveAppStatus(packageName, "installed")
        }
    }

    private fun updateEffectiveAppStatus(packageName: String, status: String) {
        try {
            val ref = database.getReference("devices/$deviceId/effectiveApps")
            ref.get()
                .addOnSuccessListener { snap ->
                    try {
                        snap.children.find {
                            it.child("packageName").getValue(String::class.java) == packageName
                        }?.ref?.child("status")?.setValue(status)
                        Log.d(TAG, "updateEffectiveAppStatus: $packageName → $status")
                    } catch (e: Exception) { Log.e(TAG, "updateEffectiveAppStatus inner: ${e.message}") }
                }
                .addOnFailureListener { Log.e(TAG, "updateEffectiveAppStatus get failed: ${it.message}") }
        } catch (e: Exception) { Log.e(TAG, "updateEffectiveAppStatus: ${e.message}") }
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

        // On successful uninstall — set status to pending_install (keep in effectiveApps)
        // On system app suspend — set status to suspended
        if (success) {
            val newStatus = if (error == "system_app_disabled") "suspended" else "pending_install"
            updateEffectiveAppStatus(packageName, newStatus)
        }
    }
}