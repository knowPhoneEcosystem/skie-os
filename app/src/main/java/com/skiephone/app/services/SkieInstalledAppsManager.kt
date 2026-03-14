package com.skiephone.app.services

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.firestore.FirebaseFirestore

class SkieInstalledAppsManager(private val context: Context) {

    private val deviceId = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ANDROID_ID
    )

    fun sync() {
        Log.d(TAG, "sync() started — deviceId: $deviceId")

        FirebaseFirestore.getInstance()
            .document("skieConfig/globalPolicy")
            .get()
            .addOnSuccessListener { snap ->
                if (!snap.exists()) {
                    Log.w(TAG, "skieConfig/globalPolicy does not exist in Firestore — pushing with empty whitelist")
                    computeAndPush(emptySet())
                    return@addOnSuccessListener
                }
                val apps = snap.get("approvedApps") as? List<Map<String, Any>> ?: emptyList()
                val globalApprovedPackages = apps.mapNotNull { it["packageName"] as? String }.toSet()
                Log.d(TAG, "Global policy loaded — ${globalApprovedPackages.size} approved packages: $globalApprovedPackages")
                computeAndPush(globalApprovedPackages)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to read global policy from Firestore: ${e.message}")
                computeAndPush(emptySet())
            }
    }

    private fun computeAndPush(globalApprovedPackages: Set<String>) {
        val pm = context.packageManager

        val allInstalled = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { appInfo ->
                val isSystem = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                !isSystem
            }
            .map { appInfo ->
                val inGlobalPolicy = globalApprovedPackages.contains(appInfo.packageName)
                mapOf(
                    "packageName" to appInfo.packageName,
                    "appName" to pm.getApplicationLabel(appInfo).toString(),
                    "whitelisted" to inGlobalPolicy
                )
            }
            .sortedBy { it["appName"] as String }

        val whitelistedCount = allInstalled.count { it["whitelisted"] == true }
        val unapprovedCount = allInstalled.count { it["whitelisted"] == false }

        Log.d(TAG, "computeAndPush: ${allInstalled.size} total installed, $whitelistedCount in global policy, $unapprovedCount unapproved")
        allInstalled.filter { it["whitelisted"] == true }.forEach {
            Log.d(TAG, "  ✓ whitelisted: ${it["appName"]} (${it["packageName"]})")
        }

        val ref = FirebaseDatabase.getInstance().getReference("devices/$deviceId")
        // installedApps = full list with whitelisted flag (used for unapproved detection)
        // whitelistedApps node is REMOVED — effectiveApps is the source of truth for what shows on launcher
        ref.child("installedApps").setValue(allInstalled)
            .addOnSuccessListener { Log.d(TAG, "installedApps written to RTDB — ${allInstalled.size} apps") }
            .addOnFailureListener { Log.e(TAG, "Failed to write installedApps: ${it.message}") }
        ref.child("unapprovedAppsCount").setValue(unapprovedCount)

        Log.d(TAG, "sync complete — deviceId: $deviceId")
    }

    companion object {
        private const val TAG = "SkieInstalledAppsManager"
    }
}