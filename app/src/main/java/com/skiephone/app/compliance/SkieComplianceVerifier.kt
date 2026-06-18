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
 * Layer 2: Effective State Verification.
 * "Did Android actually enforce it?"
 * Queries system APIs independently — does NOT rely on Layer 1 results.
 *
 * BEST-EFFORT steps (require manual verification):
 * - frp (cannot be auto-tested without factory reset attempt)
 * DNS filtering is now auto-verified after 10s propagation delay.
 */
object SkieComplianceVerifier {

    private const val TAG = "SkieComplianceVerifier"

    data class VerifyResult(
        val stepId: String,
        val label: String,
        val effective: Boolean,
        val detail: String? = null,
        val requiresManualVerification: Boolean = false
    )

    // Steps that cannot be reliably auto-verified — require physical manual testing
    val MANUAL_STEPS = setOf(
        SkiePolicyEngine.STEP_FRP
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
                val result = verifyStep(context, stepId, label)
                results.add(result)
                Log.d(TAG, "Verify $stepId → effective=${result.effective} manual=${result.requiresManualVerification}")
                Thread.sleep(250)
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                onComplete(results)
            }
        }.start()
    }

    private fun verifyStep(context: Context, stepId: String, label: String): VerifyResult {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(context, SkieDeviceAdminReceiver::class.java)
        val um = context.getSystemService(Context.USER_SERVICE) as UserManager

        // Best-effort steps auto-pass Layer 2 — scored via manual confirmation only
        if (stepId in MANUAL_STEPS) {
            return VerifyResult(stepId, label, effective = true,
                requiresManualVerification = true)
        }

        return try {
            when (stepId) {
                SkiePolicyEngine.STEP_DISALLOW_FACTORY_RESET ->
                    VerifyResult(stepId, label,
                        dpm.getUserRestrictions(admin)
                            .getBoolean(UserManager.DISALLOW_FACTORY_RESET, false))
                SkiePolicyEngine.STEP_DISALLOW_SAFE_BOOT ->
                    VerifyResult(stepId, label,
                        dpm.getUserRestrictions(admin)
                            .getBoolean(UserManager.DISALLOW_SAFE_BOOT, false))
                SkiePolicyEngine.STEP_DISALLOW_USB_FILE_TRANSFER ->
                    VerifyResult(stepId, label,
                        dpm.getUserRestrictions(admin)
                            .getBoolean(UserManager.DISALLOW_USB_FILE_TRANSFER, false))
                SkiePolicyEngine.STEP_DISALLOW_MODIFY_ACCOUNTS ->
                    VerifyResult(stepId, label,
                        dpm.getUserRestrictions(admin)
                            .getBoolean(UserManager.DISALLOW_MODIFY_ACCOUNTS, false))
                SkiePolicyEngine.STEP_DISALLOW_INSTALL_UNKNOWN ->
                    VerifyResult(stepId, label,
                        dpm.getUserRestrictions(admin)
                            .getBoolean(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES, false))
                SkiePolicyEngine.STEP_DISALLOW_DEBUGGING ->
                    VerifyResult(stepId, label,
                        dpm.getUserRestrictions(admin)
                            .getBoolean(UserManager.DISALLOW_DEBUGGING_FEATURES, false))
                SkiePolicyEngine.STEP_DISALLOW_AIRPLANE_MODE ->
                    VerifyResult(stepId, label,
                        dpm.getUserRestrictions(admin)
                            .getBoolean(UserManager.DISALLOW_AIRPLANE_MODE, false))
                SkiePolicyEngine.STEP_DISALLOW_ADD_USER ->
                    VerifyResult(stepId, label,
                        dpm.getUserRestrictions(admin)
                            .getBoolean(UserManager.DISALLOW_ADD_USER, false))
                SkiePolicyEngine.STEP_DISALLOW_USER_SWITCH ->
                    VerifyResult(stepId, label,
                        dpm.getUserRestrictions(admin)
                            .getBoolean(UserManager.DISALLOW_USER_SWITCH, false))
                SkiePolicyEngine.STEP_DISALLOW_CONFIG_MOBILE ->
                    VerifyResult(stepId, label,
                        dpm.getUserRestrictions(admin)
                            .getBoolean(UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS, false))
                SkiePolicyEngine.STEP_SCREEN_CAPTURE_DISABLED ->
                    VerifyResult(stepId, label, dpm.getScreenCaptureDisabled(admin))
                SkiePolicyEngine.STEP_BLOCK_UNINSTALL ->
                    VerifyResult(stepId, label,
                        dpm.isUninstallBlocked(admin, context.packageName))

                "block_dpc_data_clear" ->
                    VerifyResult(stepId, label,
                        dpm.isUninstallBlocked(admin, context.packageName))
                SkiePolicyEngine.STEP_DNS_FILTERING -> {
                    // Check private_dns_mode and private_dns_specifier via Settings.Global
                    // Works Android 10+ (API 29+) all OEMs — this is the OS-level DNS config
                    val mode = Settings.Global.getString(context.contentResolver, "private_dns_mode")
                    val host = Settings.Global.getString(context.contentResolver, "private_dns_specifier")
                    val active = mode == "hostname" && !host.isNullOrBlank()
                    VerifyResult(stepId, label, active,
                        if (!active) "DNS mode=$mode host=$host" else null)
                }
                else -> VerifyResult(stepId, label, false, "Unknown step")
            }
        } catch (e: Exception) {
            Log.e(TAG, "verifyStep $stepId: ${e.message}")
            VerifyResult(stepId, label, false, e.message)
        }
    }
}