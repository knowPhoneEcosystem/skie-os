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

    // Currently active focus app — always unsuspended regardless of effectivePackages
    private var activeFocusApp: String? = null

    private var effectiveAppsListener: ValueEventListener? = null
    private var policyListener: ValueEventListener? = null

    fun start() {
        Log.d(TAG, "start() deviceId:$deviceId isDeviceOwner:${isDeviceOwner()}")
        grantRequiredPermissions()
        hardenDevice()
        clearStaleSuspensions()  // clear any suspensions from previous session before applying new policy
        listenGlobalPolicy()     // load global first, then attach RTDB listeners
    }

    fun stop() {
        effectiveAppsListener?.let { rtdb.getReference("devices/$deviceId/effectiveApps").removeEventListener(it) }
        policyListener?.let { rtdb.getReference("devices/$deviceId/policy").removeEventListener(it) }
        Log.d(TAG, "stop() — listeners removed")
    }

    // Step 1: load globalPolicy from Firestore (fallback when effectiveApps is null)
    private fun listenGlobalPolicy() {
        FirebaseFirestore.getInstance().document("skieConfig/globalPolicy")
            .addSnapshotListener { snap, error ->
                if (error != null) { Log.e(TAG, "globalPolicy error: ${error.message}"); return@addSnapshotListener }
                if (snap == null || !snap.exists()) { Log.w(TAG, "globalPolicy not found"); return@addSnapshotListener }
                // Now attach RTDB listeners
                listenEffectiveApps()
                listenPolicy()
            }
    }

    // Step 2: listen to effectiveApps — if null/empty, fall back to globalPolicy
    private fun listenEffectiveApps() {
        val r = rtdb.getReference("devices/$deviceId/effectiveApps")
        r.keepSynced(true)
        effectiveAppsListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists() || snapshot.childrenCount == 0L) {
                    Log.d(TAG, "effectiveApps: null/empty — showing all installed apps")
                    effectivePackages = null  // signals "use globalPolicy"
                } else {
                    val pkgs = mutableSetOf(context.packageName)
                    snapshot.children.forEach { child ->
                        child.child("packageName").getValue(String::class.java)?.let { pkgs.add(it) }
                    }
                    Log.d(TAG, "effectiveApps: ${pkgs.size} packages — $pkgs")
                    effectivePackages = pkgs
                }
                applyAppSuspension()
            }
            override fun onCancelled(error: DatabaseError) { Log.e(TAG, "effectiveApps cancelled: ${error.message}") }
        }
        r.addValueEventListener(effectiveAppsListener!!)
    }

    private fun applyAppSuspension() {
        if (!isDeviceOwner()) { Log.w(TAG, "applyAppSuspension: not device owner"); return }

        val pm = context.packageManager
        val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { (it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0 }
            .map { it.packageName }

        if (effectivePackages != null) {
            // Always include the active focus app so it is never suspended during focus mode
            val allowed = effectivePackages!! + context.packageName +
                    listOfNotNull(activeFocusApp)
            val toSuspend   = installed.filter { it !in allowed }.toTypedArray()
            val toUnsuspend = installed.filter { it in allowed  }.toTypedArray()
            Log.d(TAG, "applyAppSuspension [effectiveApps]: suspending ${toSuspend.size}, unsuspending ${toUnsuspend.size}")
            Log.d(TAG, "  suspend: ${toSuspend.toList()}")
            Log.d(TAG, "  unsuspend: ${toUnsuspend.toList()}")
            try {
                if (toSuspend.isNotEmpty())   dpm.setPackagesSuspended(admin, toSuspend, true)
                if (toUnsuspend.isNotEmpty()) dpm.setPackagesSuspended(admin, toUnsuspend, false)
            } catch (e: Exception) { Log.e(TAG, "setPackagesSuspended failed: ${e.message}") }
        } else {
            val toUnsuspend = installed.toTypedArray()
            Log.d(TAG, "applyAppSuspension [no effectiveApps]: unsuspending all ${toUnsuspend.size} apps")
            try {
                if (toUnsuspend.isNotEmpty()) dpm.setPackagesSuspended(admin, toUnsuspend, false)
            } catch (e: Exception) { Log.e(TAG, "setPackagesSuspended failed: ${e.message}") }
        }
    }

    private fun listenPolicy() {
        val r = rtdb.getReference("devices/$deviceId/policy")
        r.keepSynced(true)
        policyListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) return
                val cameraDisabled    = snapshot.child("cameraDisabled").getValue(Boolean::class.java) ?: false
                val schoolModeEnabled = snapshot.child("schoolModeEnabled").getValue(Boolean::class.java) ?: false
                val screenLockEnabled = snapshot.child("screenLockEnabled").getValue(Boolean::class.java) ?: false
                val focusMode         = snapshot.child("focusMode").getValue(String::class.java) ?: "none"
                val focusApp          = snapshot.child("focusApp").getValue(String::class.java)
                Log.d(TAG, "policy: camera=$cameraDisabled school=$schoolModeEnabled lock=$screenLockEnabled focusMode=$focusMode focusApp=$focusApp")
                SkiePolicyCache.save(context, snapshot)
                setCameraDisabled(cameraDisabled)
                setSchoolMode(schoolModeEnabled)
                if (screenLockEnabled) lockScreen()
                applyFocusMode(focusMode, focusApp)
                SkieInstalledAppsManager(context).sync()
                SkiePolicyState.notifyUpdated()
            }
            override fun onCancelled(error: DatabaseError) { Log.e(TAG, "policy cancelled: ${error.message}") }
        }
        r.addValueEventListener(policyListener!!)
    }

    fun applyFocusMode(focusMode: String, focusApp: String?) {
        if (!isDeviceOwner()) return
        val launcher = context.packageName
        Log.d(TAG, "applyFocusMode: mode=$focusMode app=$focusApp")

        when (focusMode) {
            "none" -> {
                activeFocusApp = null  // clear before reapplying suspension
                try {
                    dpm.setLockTaskPackages(admin, arrayOf(launcher))
                    Log.d(TAG, "focus none — lockTask packages reset to launcher only")
                } catch (e: Exception) { Log.e(TAG, "focus none setLockTaskPackages: ${e.message}") }
                applyAppSuspension()  // reapply — focus app no longer exempt
                // Broadcast AFTER setLockTaskPackages — guaranteed ordering
                sendFocusBroadcast("none", null)
            }
            "focus_time" -> {
                activeFocusApp = focusApp  // unsuspend this app before lock task
                applyAppSuspension()       // punch hole in suspension for focus app
                val pkgs = if (!focusApp.isNullOrEmpty()) arrayOf(launcher, focusApp) else arrayOf(launcher)
                try {
                    dpm.setLockTaskPackages(admin, pkgs)
                    Log.d(TAG, "focus_time — lockTask packages set: ${pkgs.toList()}")
                } catch (e: Exception) { Log.e(TAG, "focus_time setLockTaskPackages: ${e.message}") }
                // Broadcast AFTER — launcher calls startLockTask on receive, packages already set
                sendFocusBroadcast("focus_time", focusApp)
            }
            "deep_focus" -> {
                activeFocusApp = focusApp  // unsuspend this app before lock task
                applyAppSuspension()       // punch hole in suspension for focus app
                val pkgs = if (!focusApp.isNullOrEmpty()) arrayOf(launcher, focusApp) else arrayOf(launcher)
                try {
                    dpm.setLockTaskPackages(admin, pkgs)
                    Log.d(TAG, "deep_focus — lockTask packages set: ${pkgs.toList()}")
                } catch (e: Exception) { Log.e(TAG, "deep_focus setLockTaskPackages: ${e.message}") }
                // Broadcast AFTER — launcher startLockTask + launches focusApp on receive
                sendFocusBroadcast("deep_focus", focusApp)
            }
        }
    }

    // Local broadcast — always sent AFTER setLockTaskPackages, eliminating the race condition
    private fun sendFocusBroadcast(mode: String, focusApp: String?) {
        val intent = Intent(SkieFocusConstants.ACTION_FOCUS_CHANGED).apply {
            setPackage(context.packageName)
            putExtra(SkieFocusConstants.EXTRA_FOCUS_MODE, mode)
            if (!focusApp.isNullOrEmpty()) putExtra(SkieFocusConstants.EXTRA_FOCUS_APP, focusApp)
        }
        context.sendBroadcast(intent)
        Log.d(TAG, "sendFocusBroadcast: mode=$mode app=$focusApp")
    }

    private fun clearStaleSuspensions() {
        if (!isDeviceOwner()) return
        try {
            val pm = context.packageManager
            val all = pm.getInstalledApplications(android.content.pm.PackageManager.GET_META_DATA)
                .filter { (it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0 }
                .map { it.packageName }
                .toTypedArray()
            if (all.isNotEmpty()) dpm.setPackagesSuspended(admin, all, false)
            Log.d(TAG, "clearStaleSuspensions: unsuspended ${all.size} apps")
        } catch (e: Exception) { Log.e(TAG, "clearStaleSuspensions failed: ${e.message}") }
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
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION).forEach {
            try {
                dpm.setPermissionGrantState(admin, context.packageName, it, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
            } catch (e: Exception) { Log.e(TAG, "grant $it: ${e.message}") }
        }
    }

    fun setCameraDisabled(disabled: Boolean) {
        if (!isDeviceOwner()) return
        dpm.setCameraDisabled(admin, disabled)
        Log.d(TAG, "camera disabled: $disabled")
    }

    fun lockScreen() {
        if (!isDeviceOwner()) return
        dpm.lockNow()
    }

    fun setSchoolMode(enabled: Boolean) {
        if (!isDeviceOwner()) return
        dpm.setKeyguardDisabledFeatures(admin,
            if (enabled) DevicePolicyManager.KEYGUARD_DISABLE_FEATURES_ALL
            else DevicePolicyManager.KEYGUARD_DISABLE_FEATURES_NONE)
    }

    fun isDeviceOwner() = dpm.isDeviceOwnerApp(context.packageName)

    fun requestDeviceAdmin() {
        context.startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Skie requires device admin to manage policies.")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    companion object { private const val TAG = "SkiePolicyEngine" }
}