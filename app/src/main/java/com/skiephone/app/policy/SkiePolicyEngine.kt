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

    // globalPolicy packages — always unsuspended regardless of effectiveApps state
    // These are provisioning-time approved apps (Calculator, Camera, Contacts etc.)
    // Stored for reference (e.g. App Management screen pre-seeding of locked toggles)
    private var globalPolicyPackages: Set<String> = emptySet()

    // School mode — when true, only school-approved packages are unsuspended
    private var _schoolModeEnabled: Boolean = false

    // Currently active focus app — always unsuspended regardless of effectivePackages
    private var activeFocusApp: String? = null

    private var effectiveAppsListener: com.google.firebase.database.ChildEventListener? = null
    private var policyListener: ValueEventListener? = null
    private var listenersStarted = false

    fun start() {
        Log.d(TAG, "start() deviceId:$deviceId isDeviceOwner:${isDeviceOwner()}")
        grantRequiredPermissions()
        repinLauncherHome()
        clearStaleSuspensions()
        // Pre-load globalPolicy from RTDB cache immediately — available before Firestore auth
        rtdb.getReference("devices/$deviceId/globalPolicyCache")
            .get()
            .addOnSuccessListener { snap ->
                if (snap.exists()) {
                    globalPolicyPackages = snap.children
                        .mapNotNull { it.getValue(String::class.java) }
                        .toSet()
                    Log.d(TAG, "start: globalPolicyCache pre-loaded — ${globalPolicyPackages.size} packages")
                }
            }
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

                // Store globalPolicy packages for reference (e.g. App Management screen pre-seeding)
                val apps = snap.get("approvedApps") as? List<Map<String, Any>> ?: emptyList()
                val newGlobalPackages = apps.mapNotNull { it["packageName"] as? String }.toSet()
                globalPolicyPackages = newGlobalPackages
                Log.d(TAG, "globalPolicy loaded — ${globalPolicyPackages.size} packages")

                // Guard against duplicate listener registration on Firestore reconnects
                if (!listenersStarted) {
                    listenersStarted = true
                    listenEffectiveApps()
                    listenPolicy()
                } else {
                    // Global policy updated after initial load — enforce any new packages
                    // directly (always-allowed, per app). Does NOT write into effectiveApps,
                    // since that node is array-indexed (legacy dashboard structure) and
                    // writing a package-keyed path there would create inconsistent shapes.
                    newGlobalPackages.forEach { pkg -> enforceOneApp(pkg, true) }
                }
            }
    }

    /**
     * Single source of truth: devices/{deviceId}/effectiveApps/{index} = {packageName, allowed, ...}
     * Per-app enforcement only — no bulk re-scan of installed apps on every change.
     * NOTE: effectiveApps is array-indexed (legacy dashboard structure) — packageName is
     * always read from the child's data field, never from snapshot.key.
     */
    private fun listenEffectiveApps() {
        val r = rtdb.getReference("devices/$deviceId/effectiveApps")
        r.keepSynced(true)
        effectiveAppsListener = object : com.google.firebase.database.ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                enforceFromSnapshot(snapshot)
            }
            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                enforceFromSnapshot(snapshot)
            }
            override fun onChildRemoved(snapshot: DataSnapshot) { /* no-op: removal does not change suspension */ }
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) { /* no-op */ }
            override fun onCancelled(error: DatabaseError) { Log.e(TAG, "effectiveApps cancelled: ${error.message}") }
        }
        r.addChildEventListener(effectiveAppsListener!!)
    }

    private fun enforceFromSnapshot(snapshot: DataSnapshot) {
        val pkg = snapshot.child("packageName").getValue(String::class.java) ?: snapshot.key ?: return
        val rawAllowed = snapshot.child("allowed").value
        val allowed = when (rawAllowed) {
            is Boolean -> rawAllowed
            is Long    -> rawAllowed != 0L
            is String  -> rawAllowed != "false"
            else       -> true
        }
        enforceOneApp(pkg, allowed)
    }

    /**
     * Enforce suspension/hidden state for exactly one package. Synchronous DPM calls —
     * dpm.setPackagesSuspended()/setApplicationHidden() either throw or succeed immediately,
     * no async OS propagation to wait for (unlike DNS/FRP). No RTDB writeback — writing
     * confirmation back to the same node this listener watches caused an infinite
     * self-triggering loop. Callers (UI or this listener) already know the result
     * synchronously via the returned Boolean.
     *
     * Public so the App Management "Save Changes" flow can invoke this directly after
     * its own RTDB write succeeds, instead of going through a listener round-trip.
     */
    fun enforceOneApp(pkg: String, allowed: Boolean): Boolean {
        if (pkg == context.packageName) return true // never touch self
        if (!isDeviceOwner()) { Log.w(TAG, "enforceOneApp: not device owner"); return false }
        return try {
            if (allowed) {
                dpm.setApplicationHidden(admin, pkg, false)
                dpm.setPackagesSuspended(admin, arrayOf(pkg), false)
            } else {
                dpm.setPackagesSuspended(admin, arrayOf(pkg), true)
                dpm.setApplicationHidden(admin, pkg, true)
            }
            Log.d(TAG, "enforceOneApp: $pkg allowed=$allowed success=true")
            true
        } catch (e: Exception) {
            Log.e(TAG, "enforceOneApp $pkg allowed=$allowed: ${e.message}")
            false
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
                    val dnsEnabled        = snapshot.child("dnsFilteringEnabled").getValue(Boolean::class.java) ?: true
                    Log.d(TAG, "policy: camera=$cameraDisabled school=$schoolModeEnabled lock=$screenLockEnabled focus=$focusMode")
                    SkiePolicyCache.save(context, snapshot)
                    setCameraDisabled(cameraDisabled)
                    applyDnsFiltering(dnsEnabled)
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
                    sendFocusBroadcast("none", null)
                }
                "focus_time" -> {
                    activeFocusApp = focusApp
                    val pkgs = if (!focusApp.isNullOrEmpty()) arrayOf(launcher, focusApp) else arrayOf(launcher)
                    try { dpm.setLockTaskPackages(admin, pkgs) } catch (e: Exception) { Log.e(TAG, "focus_time setLockTask: ${e.message}") }
                    sendFocusBroadcast("focus_time", focusApp)
                }
                "deep_focus" -> {
                    activeFocusApp = focusApp
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
            applyDnsFiltering(true) // step 31 — lock DNS to NextDNS
            dpm.addUserRestriction(admin, UserManager.DISALLOW_FACTORY_RESET)
            dpm.addUserRestriction(admin, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
            dpm.addUserRestriction(admin, UserManager.DISALLOW_MODIFY_ACCOUNTS)
            dpm.addUserRestriction(admin, UserManager.DISALLOW_USB_FILE_TRANSFER)
            dpm.addUserRestriction(admin, UserManager.DISALLOW_SAFE_BOOT)
            // Block default app changes from Settings UI — API 34+ only, no-op on older OS
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            }
            applyFrpPolicy(true)
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

        // Grant SYSTEM_ALERT_WINDOW via AppOpsManager reflection so TYPE_APPLICATION_OVERLAY
        // windows work without user interaction. setMode is a hidden API — must use reflection.
        // OP_SYSTEM_ALERT_WINDOW = 24, MODE_ALLOWED = 0.
        // Called on every service start — idempotent, persists across restarts.
        try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE)
            val setModeMethod = appOps!!.javaClass.getDeclaredMethod(
                "setMode",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                String::class.java,
                Int::class.javaPrimitiveType
            )
            setModeMethod.invoke(appOps, 24, context.applicationInfo.uid, context.packageName, 0)
            Log.d(TAG, "grantRequiredPermissions: SYSTEM_ALERT_WINDOW granted via AppOps reflection")
        } catch (e: Exception) {
            Log.e(TAG, "grantRequiredPermissions: SYSTEM_ALERT_WINDOW AppOps grant failed: ${e.message}")
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
            applyFrpPolicy(false)
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
                "block_uninstall_skie"         -> dpm.setUninstallBlocked(admin, context.packageName, true)
                "dns_filtering"                -> applyDnsFiltering(true)
                STEP_FRP                       -> applyFrpPolicy(true)
                else -> Log.w(TAG, "applyHardeningStep: unknown stepId=$stepId")
            }
            Log.d(TAG, "applyHardeningStep: $stepId applied")
        } catch (e: Exception) { Log.e(TAG, "applyHardeningStep $stepId: ${e.message}") }
    }


    /**
     * Remove a single hardening step — reverse of applyHardeningStep.
     * Called by REMOVE_HARDENING_STEP command from Owner Dashboard toggle.
     */
    fun removeHardeningStep(stepId: String) {
        if (!isDeviceOwner()) return
        try {
            when (stepId) {
                "disallow_factory_reset"           -> dpm.clearUserRestriction(admin, UserManager.DISALLOW_FACTORY_RESET)
                "disallow_safe_boot"               -> dpm.clearUserRestriction(admin, UserManager.DISALLOW_SAFE_BOOT)
                "disallow_usb_file_transfer"       -> dpm.clearUserRestriction(admin, UserManager.DISALLOW_USB_FILE_TRANSFER)
                "disallow_modify_accounts"         -> dpm.clearUserRestriction(admin, UserManager.DISALLOW_MODIFY_ACCOUNTS)
                "disallow_install_unknown_sources" -> dpm.clearUserRestriction(admin, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
                "disallow_debugging_features"      -> dpm.clearUserRestriction(admin, UserManager.DISALLOW_DEBUGGING_FEATURES)
                "disallow_airplane_mode"           -> dpm.clearUserRestriction(admin, UserManager.DISALLOW_AIRPLANE_MODE)
                "disallow_add_user"                -> dpm.clearUserRestriction(admin, UserManager.DISALLOW_ADD_USER)
                "disallow_user_switch"             -> dpm.clearUserRestriction(admin, UserManager.DISALLOW_USER_SWITCH)
                "disallow_config_mobile_networks"  -> dpm.clearUserRestriction(admin, UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS)
                "screen_capture_disabled"          -> dpm.setScreenCaptureDisabled(admin, false)
                "block_uninstall_skie"             -> dpm.setUninstallBlocked(admin, context.packageName, false)
                "dns_filtering"                    -> applyDnsFiltering(false)
                STEP_FRP                           -> applyFrpPolicy(false)
                else -> Log.w(TAG, "removeHardeningStep: unknown stepId=$stepId")
            }
            Log.d(TAG, "removeHardeningStep: $stepId removed")
        } catch (e: Exception) { Log.e(TAG, "removeHardeningStep $stepId: ${e.message}") }
    }

    fun setPowerOffDisabled(disabled: Boolean) {
        try {
            // API 35+ only — use string literal to avoid compile error on lower API
            val pm = context.packageManager
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


    /**
     * Apply or remove DNS filtering via Private DNS (DNS-over-TLS).
     * Hostname fetched from Firestore skieConfig/dnsConfig so owner can change it remotely.
     * Child cannot override — setGlobalPrivateDns is device-owner only and greys out Settings.
     */
    fun applyDnsFiltering(enabled: Boolean, onSuccess: (() -> Unit)? = null) {
        if (!isDeviceOwner()) return
        try {
            if (enabled) {
                FirebaseFirestore.getInstance().document("skieConfig/dnsConfig")
                    .get()
                    .addOnSuccessListener { snap ->
                        val hostname = snap.getString("hostname") ?: "28a418.dns.nextdns.io"
                        applyPrivateDns(hostname, onSuccess)
                    }
                    .addOnFailureListener {
                        applyPrivateDns("28a418.dns.nextdns.io", onSuccess)
                    }
            } else {
                removePrivateDns(onSuccess)
            }
        } catch (e: Exception) {
            Log.e(TAG, "applyDnsFiltering: ${e.message}")
        }
    }


    private fun applyPrivateDns(hostname: String, onSuccess: (() -> Unit)? = null) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
            Log.w(TAG, "applyPrivateDns: requires API 29+, current=${android.os.Build.VERSION.SDK_INT}")
            return
        }
        // setGlobalPrivateDnsModeSpecifiedHost does a TCP connectivity check — MUST run off main thread
        Thread {
            try {
                val method = dpm.javaClass.getMethod(
                    "setGlobalPrivateDnsModeSpecifiedHost",
                    android.content.ComponentName::class.java,
                    String::class.java
                )
                val result = method.invoke(dpm, admin, hostname)
                val code = (result as? Int) ?: result?.toString()?.toIntOrNull() ?: -1
                when (code) {
                    0 -> {
                        Log.d(TAG, "applyPrivateDns: DNS locked to $hostname ✓")
                        onSuccess?.invoke() // signal caller to write hardenLog
                    }
                    1    -> Log.e(TAG, "applyPrivateDns: FAILURE_SETTING — check device owner status")
                    2    -> Log.e(TAG, "applyPrivateDns: HOST_NOT_SERVING — TLS check failed for $hostname")
                    else -> Log.e(TAG, "applyPrivateDns: unknown code=$code result=$result")
                }
            } catch (e: java.lang.reflect.InvocationTargetException) {
                val cause = e.cause ?: e.targetException
                Log.e(TAG, "applyPrivateDns cause: ${cause?.javaClass?.name} — ${cause?.message}")
            } catch (e: Exception) {
                Log.e(TAG, "applyPrivateDns: ${e.javaClass.name} — ${e.message}")
            }
        }.start()
    }

    private fun removePrivateDns(onSuccess: (() -> Unit)? = null) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return
        Thread {
            try {
                val method = dpm.javaClass.getMethod(
                    "setGlobalPrivateDnsModeOpportunistic",
                    android.content.ComponentName::class.java
                )
                val result = method.invoke(dpm, admin)
                val code = (result as? Int) ?: result?.toString()?.toIntOrNull() ?: -1
                if (code == 0) {
                    Log.d(TAG, "removePrivateDns: DNS filtering removed ✓")
                    onSuccess?.invoke()
                } else Log.e(TAG, "removePrivateDns: failed code=$code")
            } catch (e: java.lang.reflect.InvocationTargetException) {
                val cause = e.cause ?: e.targetException
                Log.e(TAG, "removePrivateDns cause: ${cause?.javaClass?.name} — ${cause?.message}")
            } catch (e: Exception) {
                Log.e(TAG, "removePrivateDns: ${e.javaClass.name} — ${e.message}")
            }
        }.start()
    }

    /**
     * Apply or remove the hardware-backed Factory Reset Protection (FRP) policy.
     * When enabled: only the Skie admin Google account can set up the device after a wipe.
     * When disabled: clears the DPM FRP policy (standard FRP still applies via logged-in accounts).
     * Requires API 30+ (minSdk already >= 30).
     * Idempotent — safe to call on every boot if policy is already set.
     */
    fun applyFrpPolicy(enabled: Boolean) {
        if (!isDeviceOwner()) return
        try {
            if (enabled) {
                val policy = android.app.admin.FactoryResetProtectionPolicy.Builder()
                    .setFactoryResetProtectionAccounts(listOf(FRP_ADMIN_ACCOUNT_ID))
                    .setFactoryResetProtectionEnabled(true)
                    .build()
                dpm.setFactoryResetProtectionPolicy(admin, policy)
                // Force GMS to sync the new FRP config to the hardware-backed partition immediately
                val syncIntent = android.content.Intent("com.google.android.gms.auth.FRP_CONFIG_CHANGED").apply {
                    setPackage("com.google.android.gms")
                    addFlags(android.content.Intent.FLAG_RECEIVER_FOREGROUND)
                }
                context.sendBroadcast(syncIntent)
                Log.d(TAG, "applyFrpPolicy: FRP hardened with admin account $FRP_ADMIN_ACCOUNT_ID")
            } else {
                dpm.setFactoryResetProtectionPolicy(admin, null)
                Log.d(TAG, "applyFrpPolicy: FRP policy cleared")
            }
        } catch (e: Exception) {
            Log.e(TAG, "applyFrpPolicy enabled=$enabled: ${e.message}")
        }
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
        const val STEP_BLOCK_UNINSTALL               = "block_uninstall_skie"
        const val STEP_DNS_FILTERING                 = "dns_filtering"
        const val STEP_FRP                           = "frp_hardening"

        // Numeric Google Account ID for the Skie admin account.
        // This is the only account allowed to set up the device after a factory reset.
        // Obtained from: https://people.googleapis.com/v1/people/me?personFields=metadata
        private const val FRP_ADMIN_ACCOUNT_ID = "100099975648958309151"
    }

    fun applySingleHardeningStep(stepId: String) {
        if (!isDeviceOwner()) return
        when (stepId) {
            STEP_DISALLOW_FACTORY_RESET     -> dpm.addUserRestriction(admin, android.os.UserManager.DISALLOW_FACTORY_RESET)
            STEP_DISALLOW_SAFE_BOOT         -> dpm.addUserRestriction(admin, android.os.UserManager.DISALLOW_SAFE_BOOT)
            STEP_DISALLOW_USB_FILE_TRANSFER -> dpm.addUserRestriction(admin, android.os.UserManager.DISALLOW_USB_FILE_TRANSFER)
            STEP_DISALLOW_MODIFY_ACCOUNTS   -> dpm.addUserRestriction(admin, android.os.UserManager.DISALLOW_MODIFY_ACCOUNTS)
            STEP_DISALLOW_INSTALL_UNKNOWN   -> dpm.addUserRestriction(admin, android.os.UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
            STEP_DISALLOW_DEBUGGING         -> dpm.addUserRestriction(admin, android.os.UserManager.DISALLOW_DEBUGGING_FEATURES)
            STEP_DISALLOW_AIRPLANE_MODE     -> dpm.addUserRestriction(admin, android.os.UserManager.DISALLOW_AIRPLANE_MODE)
            STEP_DISALLOW_ADD_USER          -> dpm.addUserRestriction(admin, android.os.UserManager.DISALLOW_ADD_USER)
            STEP_DISALLOW_USER_SWITCH       -> dpm.addUserRestriction(admin, android.os.UserManager.DISALLOW_USER_SWITCH)
            STEP_DISALLOW_CONFIG_MOBILE     -> dpm.addUserRestriction(admin, android.os.UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS)
            STEP_SCREEN_CAPTURE_DISABLED    -> dpm.setScreenCaptureDisabled(admin, true)
            STEP_BLOCK_UNINSTALL            -> dpm.setUninstallBlocked(admin, context.packageName, true)
            "block_dpc_data_clear"          -> dpm.setUninstallBlocked(admin, context.packageName, true)
            // Note: STEP_DNS_FILTERING calls engine.applyDnsFiltering() directly in SkieComplianceRunner
            // Note: STEP_FRP calls engine.applyFrpPolicy() directly in SkieComplianceRunner
        }
    }
}