package com.skiephone.app.compliance

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.skiephone.app.admin.SkieDeviceAdminReceiver
import com.skiephone.app.policy.SkiePolicyEngine

/**
 * Offboarding Step 3: Restore All Apps.
 *
 * CRITICAL SCOPE RULE: Only restores packages that are explicitly tracked in
 * devices/{deviceId}/effectiveApps in RTDB — the exact same set that App
 * Management wrote when the parent toggled apps on/off. Never touches packages
 * outside that list, and never touches SYSTEM_PROTECTED_PACKAGES regardless.
 *
 * Previously this runner called setApplicationHidden/setPackagesSuspended on
 * every installed package (350+), which on Nothing OS corrupted system package
 * state at a level that survived factory reset. The effectiveApps scope limit
 * ensures we only undo exactly what this app did, nothing more.
 */
object SkieRestoreAppsRunner {

    private const val TAG = "SkieRestoreAppsRunner"

    data class AppResult(
        val packageName: String,
        val label: String,
        val success: Boolean
    )

    fun runAll(
        context: Context,
        onProgress: (index: Int, total: Int, label: String) -> Unit,
        onComplete: (results: List<AppResult>) -> Unit
    ) {
        val deviceId = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ANDROID_ID
        )

        // Step 1: Fetch effectiveApps from RTDB to get the exact list of packages
        // this app has ever touched — restore only those, nothing else.
        FirebaseDatabase.getInstance()
            .getReference("devices/$deviceId/effectiveApps")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val packages = mutableListOf<String>()
                    snapshot.children.forEach { child ->
                        val pkg = child.child("packageName")
                            .getValue(String::class.java) ?: return@forEach
                        if (pkg.isNotBlank()) packages.add(pkg)
                    }
                    Log.d(TAG, "runAll: ${packages.size} packages in effectiveApps to restore")
                    restorePackages(context, packages, onProgress, onComplete)
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "runAll: effectiveApps fetch failed — ${error.message}")
                    // On fetch failure, restore nothing rather than risk touching
                    // packages we shouldn't. Surface an empty result to the caller.
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        onComplete(emptyList())
                    }
                }
            })
    }

    private fun restorePackages(
        context: Context,
        packages: List<String>,
        onProgress: (index: Int, total: Int, label: String) -> Unit,
        onComplete: (results: List<AppResult>) -> Unit
    ) {
        Thread {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE)
                    as DevicePolicyManager
            val admin = ComponentName(context, SkieDeviceAdminReceiver::class.java)
            val pm = context.packageManager
            val labelFlags = PackageManager.MATCH_DISABLED_COMPONENTS or
                    PackageManager.MATCH_UNINSTALLED_PACKAGES

            val results = mutableListOf<AppResult>()

            packages.forEachIndexed { index, pkg ->
                // Never touch protected system packages regardless of what's in effectiveApps.
                // This is a belt-and-suspenders guard — enforceOneApp() also blocks these,
                // but RestoreAppsRunner calls DPM directly so it must check independently.
                if (pkg in SkiePolicyEngine.SYSTEM_PROTECTED_PACKAGES_PUBLIC ||
                    pkg == context.packageName) {
                    Log.w(TAG, "restore: SKIPPING protected package $pkg")
                    return@forEachIndexed
                }

                val label = try {
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, labelFlags)).toString()
                } catch (e: Exception) { pkg }

                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    onProgress(index, packages.size, label)
                }

                val success = try {
                    dpm.setApplicationHidden(admin, pkg, false)
                    dpm.setPackagesSuspended(admin, arrayOf(pkg), false)
                    Log.d(TAG, "restore: $pkg — unhidden + unsuspended")
                    true
                } catch (e: Exception) {
                    Log.e(TAG, "restore $pkg failed: ${e.message}")
                    false
                }
                results.add(AppResult(pkg, label, success))
            }

            android.os.Handler(android.os.Looper.getMainLooper()).post {
                onComplete(results)
            }
        }.start()
    }
}