package com.skiephone.app.compliance

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.skiephone.app.admin.SkieDeviceAdminReceiver

/**
 * Offboarding Step 3: Restore All Apps.
 * Unsuspends and unhides every installed package on the device — not just the
 * ones tracked in effectiveApps, since suspensions can exist outside that list
 * (e.g. legacy entries, manual dashboard actions, or orphaned state). Goes
 * through MATCH_DISABLED_COMPONENTS | MATCH_UNINSTALLED_PACKAGES so hidden
 * apps are included in the scan, not just currently-visible ones.
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
        Thread {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = ComponentName(context, SkieDeviceAdminReceiver::class.java)
            val pm = context.packageManager

            val flags = PackageManager.MATCH_DISABLED_COMPONENTS or
                    PackageManager.MATCH_UNINSTALLED_PACKAGES or
                    PackageManager.GET_META_DATA
            val allPackages = try {
                pm.getInstalledApplications(flags)
                    .map { it.packageName }
                    .filter { it != context.packageName }
            } catch (e: Exception) {
                Log.e(TAG, "getInstalledApplications failed: ${e.message}")
                emptyList()
            }

            val results = mutableListOf<AppResult>()
            allPackages.forEachIndexed { index, pkg ->
                val label = try {
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, flags)).toString()
                } catch (e: Exception) { pkg }

                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    onProgress(index, allPackages.size, label)
                }

                val success = try {
                    dpm.setApplicationHidden(admin, pkg, false)
                    dpm.setPackagesSuspended(admin, arrayOf(pkg), false)
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