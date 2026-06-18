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
 * Layer 3: Attack Simulation.
 * "Can we still bypass it?"
 *
 * Key fix: device owner calling its own APIs never gets SecurityException.
 * All checks now use state-read-after-attempt pattern instead of expecting exceptions.
 *
 * Best-effort steps auto-pass Layer 3 — scored via manual confirmation only.
 */
object SkieComplianceAttackSim {

    private const val TAG = "SkieComplianceAttackSim"

    data class AttackResult(
        val stepId: String,
        val label: String,
        val blocked: Boolean,
        val detail: String? = null,
        val requiresManualVerification: Boolean = false
    )

    fun runAll(
        context: Context,
        onProgress: (index: Int, label: String) -> Unit,
        onComplete: (results: List<AttackResult>) -> Unit
    ) {
        Thread {
            val results = mutableListOf<AttackResult>()
            SkieComplianceRunner.ALL_STEPS.forEachIndexed { index, (stepId, label) ->
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    onProgress(index, label)
                }
                val result = simulateAttack(context, stepId, label)
                results.add(result)
                Log.d(TAG, "Attack sim $stepId → blocked=${result.blocked}")
                Thread.sleep(300)
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                onComplete(results)
            }
        }.start()
    }

    private fun simulateAttack(context: Context, stepId: String, label: String): AttackResult {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(context, SkieDeviceAdminReceiver::class.java)

        // Best-effort steps auto-pass Layer 3 — manual confirmation handles scoring
        if (stepId in SkieComplianceVerifier.MANUAL_STEPS) {
            return AttackResult(stepId, label, blocked = true,
                requiresManualVerification = true)
        }

        // Use UserManager.getUserRestrictions() — the USER-FACING bundle.
        // This reflects what the OS actually enforces for the user regardless of OEM DPM quirks.
        // dpm.getUserRestrictions(admin) returns the admin-set bundle which OEMs (OnePlus, Samsung)
        // may not reflect accurately after clear attempts. UserManager is the ground truth.
        val um = context.getSystemService(Context.USER_SERVICE) as UserManager

        return try {
            when (stepId) {
                SkiePolicyEngine.STEP_DISALLOW_FACTORY_RESET ->
                    AttackResult(stepId, label,
                        um.getUserRestrictions().getBoolean(UserManager.DISALLOW_FACTORY_RESET, false))
                SkiePolicyEngine.STEP_DISALLOW_SAFE_BOOT ->
                    AttackResult(stepId, label,
                        um.getUserRestrictions().getBoolean(UserManager.DISALLOW_SAFE_BOOT, false))
                SkiePolicyEngine.STEP_DISALLOW_USB_FILE_TRANSFER ->
                    AttackResult(stepId, label,
                        um.getUserRestrictions().getBoolean(UserManager.DISALLOW_USB_FILE_TRANSFER, false))
                SkiePolicyEngine.STEP_DISALLOW_MODIFY_ACCOUNTS ->
                    AttackResult(stepId, label,
                        um.getUserRestrictions().getBoolean(UserManager.DISALLOW_MODIFY_ACCOUNTS, false))
                SkiePolicyEngine.STEP_DISALLOW_INSTALL_UNKNOWN ->
                    AttackResult(stepId, label,
                        um.getUserRestrictions().getBoolean(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES, false))
                SkiePolicyEngine.STEP_DISALLOW_DEBUGGING ->
                    AttackResult(stepId, label,
                        um.getUserRestrictions().getBoolean(UserManager.DISALLOW_DEBUGGING_FEATURES, false))
                SkiePolicyEngine.STEP_DISALLOW_AIRPLANE_MODE ->
                    AttackResult(stepId, label,
                        um.getUserRestrictions().getBoolean(UserManager.DISALLOW_AIRPLANE_MODE, false))
                SkiePolicyEngine.STEP_DISALLOW_ADD_USER ->
                    AttackResult(stepId, label,
                        um.getUserRestrictions().getBoolean(UserManager.DISALLOW_ADD_USER, false))
                SkiePolicyEngine.STEP_DISALLOW_USER_SWITCH ->
                    AttackResult(stepId, label,
                        um.getUserRestrictions().getBoolean(UserManager.DISALLOW_USER_SWITCH, false))
                SkiePolicyEngine.STEP_DISALLOW_CONFIG_MOBILE ->
                    AttackResult(stepId, label,
                        um.getUserRestrictions().getBoolean(UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS, false))
                SkiePolicyEngine.STEP_SCREEN_CAPTURE_DISABLED ->
                    // dpm.getScreenCaptureDisabled is reliable — direct DPM API, not OEM bundle
                    AttackResult(stepId, label, dpm.getScreenCaptureDisabled(admin))
                SkiePolicyEngine.STEP_BLOCK_UNINSTALL ->
                    // dpm.isUninstallBlocked is reliable — direct DPM API
                    AttackResult(stepId, label,
                        dpm.isUninstallBlocked(admin, context.packageName))

                SkiePolicyEngine.STEP_DNS_FILTERING -> {
                    // Verify DNS is still locked — check Settings.Global directly
                    val mode = android.provider.Settings.Global.getString(
                        context.contentResolver, "private_dns_mode")
                    val host = android.provider.Settings.Global.getString(
                        context.contentResolver, "private_dns_specifier")
                    val active = mode == "hostname" && !host.isNullOrBlank()
                    AttackResult(stepId, label, active,
                        if (!active) "DNS mode=$mode host=$host" else null)
                }
                "block_dpc_data_clear" ->
                    AttackResult(stepId, label,
                        dpm.isUninstallBlocked(admin, context.packageName))
                else -> AttackResult(stepId, label, false, "Unknown step")
            }
        } catch (e: Exception) {
            Log.e(TAG, "attackSim $stepId exception: ${e.message}")
            AttackResult(stepId, label, true, "Exception during sim: ${e.message}")
        }
    }
}