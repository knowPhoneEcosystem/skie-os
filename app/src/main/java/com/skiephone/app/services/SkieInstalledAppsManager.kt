package com.skiephone.app.services

import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.firestore.FirebaseFirestore
import com.skiephone.app.admin.SkieDeviceAdminReceiver
import com.skiephone.app.admin.UpdateReceiver

class SkieInstalledAppsManager(private val context: Context) {

    private val deviceId: String = android.provider.Settings.Secure.getString(
        context.contentResolver, android.provider.Settings.Secure.ANDROID_ID
    )
    private val rtdb  = FirebaseDatabase.getInstance()
    private val dpm   = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val admin = ComponentName(context, SkieDeviceAdminReceiver::class.java)

    // ── Package install/remove listener ──────────────────────────────────────
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            try {
                val packageName = intent.data?.schemeSpecificPart ?: return
                val replacing   = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
                when (intent.action) {
                    Intent.ACTION_PACKAGE_ADDED -> {
                        if (replacing) return
                        Log.d(TAG, "Package installed: $packageName")
                        handleNewInstall(packageName)
                    }
                    Intent.ACTION_PACKAGE_REMOVED -> {
                        if (replacing) return
                        Log.d(TAG, "Package removed: $packageName")
                        handlePackageRemoved(packageName)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "packageReceiver: ${e.message}")
            }
        }
    }

    fun startListening() {
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addDataScheme("package")
            }
            context.registerReceiver(packageReceiver, filter)
            Log.d(TAG, "Package listener registered")
        } catch (e: Exception) {
            Log.e(TAG, "startListening: ${e.message}")
        }
    }

    fun stopListening() {
        try { context.unregisterReceiver(packageReceiver) }
        catch (e: Exception) { Log.w(TAG, "stopListening: ${e.message}") }
    }

    // ── Public sync — called on boot and by SkiePolicyService ────────────────
    // Strategy:
    //   1. Try RTDB cache first (instant, works offline, no auth delay)
    //   2. Simultaneously fetch fresh from Firestore
    //   3. Whichever resolves first runs computeAndPush
    //   4. Firestore result always wins if it arrives (writes RTDB cache for next boot)
    fun sync() {
        Log.d(TAG, "sync() called — deviceId: $deviceId")
        var rtdbDone = false
        var firestoreDone = false

        // ── Path 1: RTDB cache (fast, works on boot before Firestore auth) ──
        rtdb.getReference("devices/$deviceId/globalPolicyCache")
            .get()
            .addOnSuccessListener { snap ->
                if (rtdbDone || firestoreDone) return@addOnSuccessListener
                if (snap.exists()) {
                    val packages = snap.children
                        .mapNotNull { it.getValue(String::class.java) }
                        .toSet()
                    Log.d(TAG, "sync: RTDB cache hit — ${packages.size} packages")
                    rtdbDone = true
                    computeAndPush(packages)
                } else {
                    Log.d(TAG, "sync: RTDB cache empty — waiting for Firestore")
                }
            }
            .addOnFailureListener { Log.w(TAG, "sync: RTDB cache read failed — ${it.message}") }

        // ── Path 2: Firestore (authoritative, may be slow on cold boot) ──────
        FirebaseFirestore.getInstance()
            .document("skieConfig/globalPolicy")
            .get()
            .addOnSuccessListener { snap ->
                firestoreDone = true
                val apps = snap.get("approvedApps") as? List<Map<String, Any>> ?: emptyList()
                val packages = apps.mapNotNull { it["packageName"] as? String }.toSet()
                Log.d(TAG, "sync: Firestore hit — ${packages.size} packages")
                // Write RTDB cache for next boot
                rtdb.getReference("devices/$deviceId/globalPolicyCache")
                    .setValue(packages.toList())
                computeAndPush(packages)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "sync: Firestore failed — ${e.message}")
                // If RTDB cache also failed, try with empty set so installedApps is at least written
                if (!rtdbDone) {
                    Log.w(TAG, "sync: both sources failed — pushing with empty policy")
                    computeAndPush(emptySet())
                }
            }
    }

    // ── Core: build and write installedApps + allSystemApps to RTDB ──────────
    private fun computeAndPush(globalApprovedPackages: Set<String>) {
        try {
            val pm = context.packageManager

            // Use MATCH_UNINSTALLED_PACKAGES to catch apps in stopped/dormant state
            // (Samsung OEM apps like popup calculator are stopped by default on fresh ROM)
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                PackageManager.GET_META_DATA or PackageManager.MATCH_UNINSTALLED_PACKAGES
            } else {
                @Suppress("DEPRECATION")
                PackageManager.GET_META_DATA or PackageManager.GET_UNINSTALLED_PACKAGES
            }

            val allApps = try {
                pm.getInstalledApplications(flags)
            } catch (e: Exception) {
                Log.e(TAG, "getInstalledApplications failed: ${e.message}")
                // Fallback without extra flags
                try { pm.getInstalledApplications(PackageManager.GET_META_DATA) }
                catch (e2: Exception) { Log.e(TAG, "getInstalledApplications fallback failed: ${e2.message}"); return }
            }

            Log.d(TAG, "computeAndPush: ${allApps.size} total packages from PackageManager")

            // installedApps = user apps + system apps that are in globalPolicy
            // Also include system stubs that have user-installed updates (FLAG_UPDATED_SYSTEM_APP)
            // e.g. YouTube, Chrome, Gmail on Samsung ship as FLAG_SYSTEM stubs but are parent-approvable
            val installedApps = allApps
                .filter { app ->
                    val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    val isUpdatedSystemApp = (app.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                    val inPolicy = globalApprovedPackages.contains(app.packageName)
                    val isSelf   = app.packageName == context.packageName
                    // Include if:
                    //   - not self AND
                    //   - is a pure user app (not system at all), OR
                    //   - is a system app in globalPolicy, OR
                    //   - is a system stub with a user-installed update (YouTube, Chrome etc.)
                    !isSelf && (!isSystem || inPolicy || isUpdatedSystemApp)
                }
                .mapNotNull { app ->
                    val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    // Check DPM suspended state — single source of truth for display
                    val isSuspended = try {
                        if (dpm.isDeviceOwnerApp(context.packageName))
                            dpm.isPackageSuspended(admin, app.packageName)
                        else false
                    } catch (e: Exception) { false }
                    try {
                        mapOf(
                            "packageName" to app.packageName,
                            "appName"     to pm.getApplicationLabel(app).toString(),
                            "whitelisted" to globalApprovedPackages.contains(app.packageName),
                            "isSystem"    to isSystem,
                            "suspended"   to isSuspended
                        )
                    } catch (e: Exception) {
                        // getApplicationLabel can fail for some system stubs
                        mapOf(
                            "packageName" to app.packageName,
                            "appName"     to app.packageName,
                            "whitelisted" to globalApprovedPackages.contains(app.packageName),
                            "isSystem"    to isSystem,
                            "suspended"   to isSuspended
                        )
                    }
                }
                .sortedBy { it["appName"] as String }

            // allSystemApps — full list for owner bloatware removal panel only
            val allSystemApps = allApps
                .filter { app ->
                    (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    && app.packageName != context.packageName
                }
                .mapNotNull { app ->
                    try {
                        mapOf(
                            "packageName" to app.packageName,
                            "appName"     to pm.getApplicationLabel(app).toString(),
                            "isSystem"    to true
                        )
                    } catch (e: Exception) {
                        mapOf(
                            "packageName" to app.packageName,
                            "appName"     to app.packageName,
                            "isSystem"    to true
                        )
                    }
                }

            // unapproved = user apps not in policy (system apps never count as unapproved)
            val unapprovedCount = installedApps.count {
                it["whitelisted"] == false && it["isSystem"] == false
            }

            Log.d(TAG, "computeAndPush: ${installedApps.size} installedApps " +
                "(${installedApps.count { it["whitelisted"] == true }} whitelisted, " +
                "${allSystemApps.size} allSystemApps, $unapprovedCount unapproved)")
            installedApps.filter { it["whitelisted"] == true }.forEach {
                Log.d(TAG, "  ✓ whitelisted: ${it["appName"]} (${it["packageName"]}) sys=${it["isSystem"]}")
            }
            if (globalApprovedPackages.isNotEmpty()) {
                // Log any globalPolicy packages that aren't in installedApps — helps diagnose missing apps
                val installedPkgs = installedApps.map { it["packageName"] }.toSet()
                globalApprovedPackages.forEach { pkg ->
                    if (!installedPkgs.contains(pkg)) {
                        Log.w(TAG, "  ✗ globalPolicy pkg NOT found on device: $pkg")
                    }
                }
            }

            val ref = rtdb.getReference("devices/$deviceId")
            ref.child("installedApps").setValue(installedApps)
                .addOnSuccessListener { Log.d(TAG, "installedApps written — ${installedApps.size} entries") }
                .addOnFailureListener { Log.e(TAG, "installedApps write failed: ${it.message}") }
            ref.child("allSystemApps").setValue(allSystemApps)
                .addOnFailureListener { Log.e(TAG, "allSystemApps write failed: ${it.message}") }
            ref.child("unapprovedAppsCount").setValue(unapprovedCount)

        } catch (e: Exception) {
            Log.e(TAG, "computeAndPush: ${e.message}", e)
        }
    }

    // ── Handle new app install ────────────────────────────────────────────────
    private fun handleNewInstall(packageName: String) {
        try {
            val effectiveRef = rtdb.getReference("devices/$deviceId/effectiveApps")
            effectiveRef.get()
                .addOnSuccessListener { snap ->
                    try {
                        val existingEntry = snap.children.find {
                            it.child("packageName").getValue(String::class.java) == packageName
                        }
                        if (existingEntry != null) {
                            // Pre-approved by parent — unsuspend directly via DPM, no effectiveApps write
                            Log.d(TAG, "handleNewInstall: $packageName pre-approved — unsuspending")
                            try {
                                if (dpm.isDeviceOwnerApp(context.packageName))
                                    dpm.setPackagesSuspended(admin, arrayOf(packageName), false)
                            } catch (e: Exception) { Log.e(TAG, "handleNewInstall unsuspend: ${e.message}") }
                        } else {
                            // Not pre-approved — check globalPolicy cache
                            rtdb.getReference("devices/$deviceId/globalPolicyCache")
                                .get()
                                .addOnSuccessListener { cacheSnap ->
                                    val cachedPkgs = cacheSnap.children
                                        .mapNotNull { it.getValue(String::class.java) }.toSet()
                                    if (cachedPkgs.contains(packageName)) {
                                        // GlobalPolicy app — unsuspend directly, no effectiveApps write
                                        Log.d(TAG, "handleNewInstall: $packageName in globalPolicy — unsuspending")
                                        try {
                                            if (dpm.isDeviceOwnerApp(context.packageName))
                                                dpm.setPackagesSuspended(admin, arrayOf(packageName), false)
                                        } catch (e: Exception) { Log.e(TAG, "handleNewInstall globalPolicy unsuspend: ${e.message}") }
                                    } else {
                                        Log.d(TAG, "handleNewInstall: $packageName NOT approved — blocking")
                                        blockAndUninstall(packageName)
                                    }
                                }
                        }
                        // Always sync after any install — updates installedApps with fresh state
                        // Also write lastInstallTs so launcher fallback listener triggers grid rebuild
                        sync()
                        rtdb.getReference("devices/$deviceId/lastInstallTs")
                            .setValue(System.currentTimeMillis())
                    } catch (e: Exception) { Log.e(TAG, "handleNewInstall inner: ${e.message}") }
                }
                .addOnFailureListener { Log.e(TAG, "handleNewInstall get: ${it.message}") }
        } catch (e: Exception) { Log.e(TAG, "handleNewInstall: ${e.message}") }
    }

    private fun handlePackageRemoved(packageName: String) {
        try {
            // Sync immediately — installedApps will no longer contain this package
            // Dashboard derives pending_install from absence in installedApps, no effectiveApps write needed
            Log.d(TAG, "handlePackageRemoved: $packageName — triggering sync")
            sync()
            rtdb.getReference("devices/$deviceId/lastInstallTs")
                .setValue(System.currentTimeMillis())
        } catch (e: Exception) { Log.e(TAG, "handlePackageRemoved: ${e.message}") }
    }

    // ── Block + uninstall unapproved app ──────────────────────────────────────
    private fun blockAndUninstall(packageName: String) {
        try {
            val pm      = context.packageManager
            val appName = try { pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString() }
                          catch (e: Exception) { packageName }
            try {
                if (dpm.isDeviceOwnerApp(context.packageName))
                    dpm.setPackagesSuspended(admin, arrayOf(packageName), true)
            } catch (e: Exception) { Log.e(TAG, "blockAndUninstall suspend: ${e.message}") }
            try {
                val pi = PendingIntent.getBroadcast(
                    context, packageName.hashCode(),
                    Intent(context, UpdateReceiver::class.java).apply {
                        action = "com.skiephone.app.UNINSTALL_COMPLETE"
                        putExtra("packageName", packageName)
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )
                pm.packageInstaller.uninstall(packageName, pi.intentSender)
            } catch (e: Exception) { Log.e(TAG, "blockAndUninstall uninstall: ${e.message}") }
            try {
                rtdb.getReference("devices/$deviceId/blockedInstallAttempts/${packageName.replace(".", "_")}")
                    .setValue(mapOf("packageName" to packageName, "appName" to appName,
                        "blockedAt" to System.currentTimeMillis()))
            } catch (e: Exception) { Log.e(TAG, "blockAndUninstall notify: ${e.message}") }
        } catch (e: Exception) { Log.e(TAG, "blockAndUninstall: ${e.message}") }
    }

    companion object {
        private const val TAG = "SkieInstalledAppsManager"
    }
}