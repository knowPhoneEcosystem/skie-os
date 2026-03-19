package com.skiephone.app.services

import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.util.Log
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.firestore.FirebaseFirestore
import com.skiephone.app.admin.SkieDeviceAdminReceiver
import com.skiephone.app.admin.UpdateReceiver

class SkieInstalledAppsManager(private val context: Context) {

    private val deviceId: String = android.provider.Settings.Secure.getString(
        context.contentResolver, android.provider.Settings.Secure.ANDROID_ID
    )
    private val rtdb = FirebaseDatabase.getInstance()
    private val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val admin = ComponentName(context, SkieDeviceAdminReceiver::class.java)

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            try {
                val packageName = intent.data?.schemeSpecificPart ?: return
                val replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
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
                Log.e(TAG, "packageReceiver onReceive: ${e.message}")
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
            Log.d(TAG, "Package install/remove listener registered")
        } catch (e: Exception) {
            Log.e(TAG, "startListening: ${e.message}")
        }
    }

    fun stopListening() {
        try {
            context.unregisterReceiver(packageReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "stopListening: receiver not registered — ${e.message}")
        }
    }

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
                            Log.d(TAG, "handleNewInstall: $packageName was pre-approved — updating status")
                            existingEntry.ref.child("status").setValue("installed")
                            try {
                                if (dpm.isDeviceOwnerApp(context.packageName)) {
                                    dpm.setPackagesSuspended(admin, arrayOf(packageName), false)
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "handleNewInstall unsuspend: ${e.message}")
                            }
                            sync()
                            return@addOnSuccessListener
                        }
                        loadGlobalPolicy { approvedPackages ->
                            if (approvedPackages.contains(packageName)) {
                                Log.d(TAG, "handleNewInstall: $packageName in globalPolicy — auto-approving")
                                autoApproveApp(packageName)
                            } else {
                                Log.d(TAG, "handleNewInstall: $packageName NOT approved — blocking")
                                blockAndUninstall(packageName)
                            }
                            sync()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "handleNewInstall inner: ${e.message}")
                    }
                }
                .addOnFailureListener { Log.e(TAG, "handleNewInstall get failed: ${it.message}") }
        } catch (e: Exception) {
            Log.e(TAG, "handleNewInstall: ${e.message}")
        }
    }

    private fun handlePackageRemoved(packageName: String) {
        try {
            val effectiveRef = rtdb.getReference("devices/$deviceId/effectiveApps")
            effectiveRef.get()
                .addOnSuccessListener { snap ->
                    try {
                        snap.children.find {
                            it.child("packageName").getValue(String::class.java) == packageName
                        }?.ref?.child("status")?.setValue("pending_install")
                        Log.d(TAG, "handlePackageRemoved: $packageName status → pending_install")
                    } catch (e: Exception) {
                        Log.e(TAG, "handlePackageRemoved inner: ${e.message}")
                    }
                }
                .addOnFailureListener { Log.e(TAG, "handlePackageRemoved get failed: ${it.message}") }
            sync()
        } catch (e: Exception) {
            Log.e(TAG, "handlePackageRemoved: ${e.message}")
        }
    }

    private fun autoApproveApp(packageName: String) {
        try {
            val pm = context.packageManager
            val appName = try {
                pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
            } catch (e: Exception) { packageName }

            val ref = rtdb.getReference("devices/$deviceId/effectiveApps")
            ref.get()
                .addOnSuccessListener { snapshot ->
                    try {
                        val current = mutableListOf<Map<String, Any>>()
                        snapshot.children.forEach { child ->
                            val pkg = child.child("packageName").getValue(String::class.java) ?: return@forEach
                            if (pkg != packageName) {
                                current.add(mapOf(
                                    "packageName" to pkg,
                                    "appName" to (child.child("appName").getValue(String::class.java) ?: pkg),
                                    "category" to (child.child("category").getValue(String::class.java) ?: "other"),
                                    "approvedBy" to (child.child("approvedBy").getValue(String::class.java) ?: "skieos"),
                                    "allowed" to (child.child("allowed").getValue(Boolean::class.java) ?: true),
                                    "status" to (child.child("status").getValue(String::class.java) ?: "installed")
                                ))
                            }
                        }
                        current.add(mapOf(
                            "packageName" to packageName,
                            "appName" to appName,
                            "category" to "other",
                            "approvedBy" to "skieos",
                            "allowed" to true,
                            "status" to "installed"
                        ))
                        ref.setValue(current)
                            .addOnSuccessListener {
                                Log.d(TAG, "autoApproveApp: $packageName added")
                                try {
                                    if (dpm.isDeviceOwnerApp(context.packageName)) {
                                        dpm.setPackagesSuspended(admin, arrayOf(packageName), false)
                                        Log.d(TAG, "autoApproveApp: $packageName unsuspended")
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "autoApproveApp unsuspend: ${e.message}")
                                }
                            }
                            .addOnFailureListener { Log.e(TAG, "autoApproveApp setValue: ${it.message}") }
                    } catch (e: Exception) {
                        Log.e(TAG, "autoApproveApp inner: ${e.message}")
                    }
                }
                .addOnFailureListener { Log.e(TAG, "autoApproveApp get: ${it.message}") }
        } catch (e: Exception) {
            Log.e(TAG, "autoApproveApp: ${e.message}")
        }
    }

    private fun blockAndUninstall(packageName: String) {
        try {
            val pm = context.packageManager
            val appName = try {
                pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
            } catch (e: Exception) { packageName }

            // Step 1 — suspend immediately
            try {
                if (dpm.isDeviceOwnerApp(context.packageName)) {
                    dpm.setPackagesSuspended(admin, arrayOf(packageName), true)
                    Log.d(TAG, "blockAndUninstall: $packageName suspended")
                }
            } catch (e: Exception) {
                Log.e(TAG, "blockAndUninstall suspend: ${e.message}")
            }

            // Step 2 — uninstall silently
            try {
                val uninstallIntent = Intent(context, UpdateReceiver::class.java)
                uninstallIntent.action = "com.skiephone.app.UNINSTALL_COMPLETE"
                uninstallIntent.putExtra("packageName", packageName)
                val pendingIntent = PendingIntent.getBroadcast(
                    context, packageName.hashCode(), uninstallIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )
                pm.packageInstaller.uninstall(packageName, pendingIntent.intentSender)
                Log.d(TAG, "blockAndUninstall: uninstall initiated for $packageName")
            } catch (e: Exception) {
                Log.e(TAG, "blockAndUninstall uninstall: ${e.message}")
            }

            // Step 3 — notify parent
            try {
                val safeKey = packageName.replace(".", "_")
                rtdb.getReference("devices/$deviceId/blockedInstallAttempts/$safeKey").setValue(
                    mapOf(
                        "packageName" to packageName,
                        "appName" to appName,
                        "blockedAt" to System.currentTimeMillis()
                    )
                )
                Log.d(TAG, "blockAndUninstall: parent notified for $packageName")
            } catch (e: Exception) {
                Log.e(TAG, "blockAndUninstall notify: ${e.message}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "blockAndUninstall: ${e.message}")
        }
    }

    fun sync() {
        try {
            loadGlobalPolicy { globalApprovedPackages ->
                computeAndPush(globalApprovedPackages)
            }
        } catch (e: Exception) {
            Log.e(TAG, "sync: ${e.message}")
        }
    }

    private fun loadGlobalPolicy(callback: (Set<String>) -> Unit) {
        try {
            FirebaseFirestore.getInstance()
                .document("skieConfig/globalPolicy")
                .get()
                .addOnSuccessListener { snap ->
                    try {
                        val apps = snap.get("approvedApps") as? List<Map<String, Any>> ?: emptyList()
                        val packages = apps.mapNotNull { it["packageName"] as? String }.toSet()
                        Log.d(TAG, "loadGlobalPolicy: ${packages.size} approved packages")
                        callback(packages)
                    } catch (e: Exception) {
                        Log.e(TAG, "loadGlobalPolicy inner: ${e.message}")
                        callback(emptySet())
                    }
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "loadGlobalPolicy failed: ${e.message}")
                    callback(emptySet())
                }
        } catch (e: Exception) {
            Log.e(TAG, "loadGlobalPolicy: ${e.message}")
            callback(emptySet())
        }
    }

    private fun computeAndPush(globalApprovedPackages: Set<String>) {
        try {
            val pm = context.packageManager
            val allApps = try {
                pm.getInstalledApplications(PackageManager.GET_META_DATA)
            } catch (e: Exception) {
                Log.e(TAG, "computeAndPush getInstalledApplications: ${e.message}")
                return
            }

            // Non-system user-installed apps — always push to installedApps list
            val userApps = allApps
                .filter { (it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0 }
                .map { mapOf(
                    "packageName" to it.packageName,
                    "appName" to pm.getApplicationLabel(it).toString(),
                    "whitelisted" to globalApprovedPackages.contains(it.packageName)
                )}
                .sortedBy { it["appName"] as String }

            // System apps that are in globalPolicy — push separately to systemApps node
            // These show on launcher and dashboard but cannot be uninstalled
            val systemGlobalApps = allApps
                .filter { appInfo ->
                    (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                            && globalApprovedPackages.contains(appInfo.packageName)
                }
                .map { mapOf(
                    "packageName" to it.packageName,
                    "appName" to pm.getApplicationLabel(it).toString(),
                    "isSystem" to true
                )}

            val unapprovedCount = userApps.count { it["whitelisted"] == false }
            val ref = rtdb.getReference("devices/$deviceId")
            ref.child("installedApps").setValue(userApps)
                .addOnFailureListener { Log.e(TAG, "computeAndPush installedApps write: ${it.message}") }
            ref.child("systemGlobalApps").setValue(systemGlobalApps)
                .addOnFailureListener { Log.e(TAG, "computeAndPush systemGlobalApps write: ${it.message}") }
            ref.child("unapprovedAppsCount").setValue(unapprovedCount)
            Log.d(TAG, "sync: ${userApps.size} user apps, ${systemGlobalApps.size} system global apps, $unapprovedCount unapproved")

            // Add system global apps to effectiveApps if not already there
            addSystemAppsToEffectiveApps(systemGlobalApps, globalApprovedPackages)
        } catch (e: Exception) {
            Log.e(TAG, "computeAndPush: ${e.message}")
        }
    }

    /**
     * System apps (Phone, SMS, Camera) that are in globalPolicy should appear
     * on launcher and dashboard. Add them to effectiveApps with approvedBy=skieos
     * if not already present.
     */
    private fun addSystemAppsToEffectiveApps(
        systemGlobalApps: List<Map<String, Any>>,
        globalApprovedPackages: Set<String>
    ) {
        if (systemGlobalApps.isEmpty()) return
        try {
            val effectiveRef = rtdb.getReference("devices/$deviceId/effectiveApps")
            effectiveRef.get().addOnSuccessListener { snap ->
                try {
                    val current = mutableListOf<Map<String, Any>>()
                    val existingPkgs = mutableSetOf<String>()
                    snap.children.forEach { child ->
                        val pkg = child.child("packageName").getValue(String::class.java) ?: return@forEach
                        existingPkgs.add(pkg)
                        current.add(child.value as Map<String, Any>)
                    }
                    var added = 0
                    systemGlobalApps.forEach { sysApp ->
                        val pkg = sysApp["packageName"] as? String ?: return@forEach
                        if (!existingPkgs.contains(pkg)) {
                            current.add(mapOf(
                                "packageName" to pkg,
                                "appName" to (sysApp["appName"] as? String ?: pkg),
                                "category" to "essential",
                                "approvedBy" to "skieos",
                                "allowed" to true,
                                "status" to "installed"
                            ))
                            added++
                        }
                    }
                    if (added > 0) {
                        effectiveRef.setValue(current)
                        Log.d(TAG, "addSystemAppsToEffectiveApps: added $added system apps")
                        // Write lastInstallTs to trigger launcher refresh
                        rtdb.getReference("devices/$deviceId/lastInstallTs")
                            .setValue(System.currentTimeMillis())
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "addSystemAppsToEffectiveApps inner: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "addSystemAppsToEffectiveApps: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "SkieInstalledAppsManager"
    }
}