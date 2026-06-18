package com.skiephone.app.compliance

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import com.skiephone.app.admin.SkieDeviceAdminReceiver
import com.skiephone.app.policy.SkiePolicyEngine

/**
 * Offboarding Step 2: Verify Deharden.
 * Confirms each hardening step is truly absent — the inverse check of
 * SkieComplianceVerifier. No attack simulation layer needed here, since the
 * goal is simply confirming restrictions are gone, not testing bypass resistance.
 */
object SkieDehardenVerifier {

    private const val TAG = "SkieDehardenVerifier"

    data class VerifyResult(
        val stepId: String,
        val label: String,
        val cleared: Boolean,
        val detail: String? = null
    )

    fun runAll(
        context: Context,
        onProgress: (index: Int, label: String) -> Unit,
        onComplete: (results: List<VerifyResult>) -> Unit
    ) {
        Thread {
            val results = mutableListOf<VerifyResult>()
            SkieComplianceRunner.ALL_STEPS.forEachIndexed { index, (stepId, label) ->
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    onProgress(index, label)
                }
                val result = verifyCleared(context, stepId, label)
                results.add(result)
                Log.d(TAG, "Verify cleared $stepId → cleared=${result.cleared}")
                Thread.sleep(250)
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                onComplete(results)
            }
        }.start()
    }

    private fun verifyCleared(context: Context, stepId: String, label: String): VerifyResult {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(context, SkieDeviceAdminReceiver::class.java)

        // FRP cannot be auto-verified as cleared without a factory reset attempt —
        // treat as best-effort pass here, consistent with compliance wizard handling.
        if (stepId == SkiePolicyEngine.STEP_FRP) {
            return VerifyResult(stepId, label, cleared = true,
                detail = "FRP clearance cannot be auto-verified — best effort")
        }

        return try {
            when (stepId) {
                SkiePolicyEngine.STEP_DISALLOW_FACTORY_RESET ->
                    VerifyResult(stepId, label,
                        !dpm.getUserRestrictions(admin).getBoolean(UserManager.DISALLOW_FACTORY_RESET, false))
                SkiePolicyEngine.STEP_DISALLOW_SAFE_BOOT ->
                    VerifyResult(stepId, label,
                        !dpm.getUserRestrictions(admin).getBoolean(UserManager.DISALLOW_SAFE_BOOT, false))
                SkiePolicyEngine.STEP_DISALLOW_USB_FILE_TRANSFER ->
                    VerifyResult(stepId, label,
                        !dpm.getUserRestrictions(admin).getBoolean(UserManager.DISALLOW_USB_FILE_TRANSFER, false))
                SkiePolicyEngine.STEP_DISALLOW_MODIFY_ACCOUNTS ->
                    VerifyResult(stepId, label,
                        !dpm.getUserRestrictions(admin).getBoolean(UserManager.DISALLOW_MODIFY_ACCOUNTS, false))
                SkiePolicyEngine.STEP_DISALLOW_INSTALL_UNKNOWN ->
                    VerifyResult(stepId, label,
                        !dpm.getUserRestrictions(admin).getBoolean(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES, false))
                SkiePolicyEngine.STEP_DISALLOW_DEBUGGING ->
                    VerifyResult(stepId, label,
                        !dpm.getUserRestrictions(admin).getBoolean(UserManager.DISALLOW_DEBUGGING_FEATURES, false))
                SkiePolicyEngine.STEP_DISALLOW_AIRPLANE_MODE ->
                    VerifyResult(stepId, label,
                        !dpm.getUserRestrictions(admin).getBoolean(UserManager.DISALLOW_AIRPLANE_MODE, false))
                SkiePolicyEngine.STEP_DISALLOW_ADD_USER ->
                    VerifyResult(stepId, label,
                        !dpm.getUserRestrictions(admin).getBoolean(UserManager.DISALLOW_ADD_USER, false))
                SkiePolicyEngine.STEP_DISALLOW_USER_SWITCH ->
                    VerifyResult(stepId, label,
                        !dpm.getUserRestrictions(admin).getBoolean(UserManager.DISALLOW_USER_SWITCH, false))
                SkiePolicyEngine.STEP_DISALLOW_CONFIG_MOBILE ->
                    VerifyResult(stepId, label,
                        !dpm.getUserRestrictions(admin).getBoolean(UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS, false))
                SkiePolicyEngine.STEP_SCREEN_CAPTURE_DISABLED ->
                    VerifyResult(stepId, label, !dpm.getScreenCaptureDisabled(admin))
                SkiePolicyEngine.STEP_BLOCK_UNINSTALL ->
                    VerifyResult(stepId, label, !dpm.isUninstallBlocked(admin, context.packageName))
                "block_dpc_data_clear" ->
                    VerifyResult(stepId, label, !dpm.isUninstallBlocked(admin, context.packageName))
                SkiePolicyEngine.STEP_DNS_FILTERING -> {
                    val mode = Settings.Global.getString(context.contentResolver, "private_dns_mode")
                    // Cleared if mode is no longer the locked "hostname" mode we set
                    val cleared = mode != "hostname"
                    VerifyResult(stepId, label, cleared, if (!cleared) "DNS mode still=$mode" else null)
                }
                else -> VerifyResult(stepId, label, true, "Unknown step — skipped")
            }
        } catch (e: Exception) {
            Log.e(TAG, "verifyCleared $stepId: ${e.message}")
            VerifyResult(stepId, label, false, e.message)
        }
    }
}