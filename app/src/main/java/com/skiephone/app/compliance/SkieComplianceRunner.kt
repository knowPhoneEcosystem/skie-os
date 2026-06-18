package com.skiephone.app.compliance

import android.content.Context
import android.util.Log
import com.skiephone.app.policy.SkiePolicyEngine

/**
 * Layer 1: Policy Application.
 * "Was the policy applied?"
 * Calls SkiePolicyEngine hardening methods and reports pass/fail per step.
 */
object SkieComplianceRunner {

    private const val TAG = "SkieComplianceRunner"

    data class StepResult(
        val stepId: String,
        val label: String,
        val success: Boolean,
        val error: String? = null
    )

    // All 15 hardening steps in order
    val ALL_STEPS = listOf(
        SkiePolicyEngine.STEP_DISALLOW_FACTORY_RESET     to "Disable factory reset",
        SkiePolicyEngine.STEP_DISALLOW_SAFE_BOOT         to "Disable safe boot",
        SkiePolicyEngine.STEP_DISALLOW_USB_FILE_TRANSFER to "Disable USB file transfer",
        SkiePolicyEngine.STEP_DISALLOW_MODIFY_ACCOUNTS   to "Disable account modification",
        SkiePolicyEngine.STEP_DISALLOW_INSTALL_UNKNOWN   to "Block unknown sources",
        SkiePolicyEngine.STEP_DISALLOW_DEBUGGING         to "Disable ADB / debugging",
        SkiePolicyEngine.STEP_DISALLOW_AIRPLANE_MODE     to "Disable airplane mode toggle",
        SkiePolicyEngine.STEP_DISALLOW_ADD_USER          to "Disable adding users",
        SkiePolicyEngine.STEP_DISALLOW_USER_SWITCH       to "Disable user switching",
        SkiePolicyEngine.STEP_DISALLOW_CONFIG_MOBILE     to "Disable mobile network config",
        SkiePolicyEngine.STEP_SCREEN_CAPTURE_DISABLED    to "Disable screen capture",
        SkiePolicyEngine.STEP_BLOCK_UNINSTALL            to "Block SkieOS uninstall",
        SkiePolicyEngine.STEP_DNS_FILTERING              to "DNS filtering (NextDNS)",
        SkiePolicyEngine.STEP_FRP                        to "Factory Reset Protection (FRP)",
        "block_dpc_data_clear"                           to "Block SkieOS data clear"
    )

    /**
     * Run all hardening steps via SkiePolicyEngine.
     * Reports progress via [onProgress] with step label and index.
     * Returns list of results after all steps complete.
     */
    fun runAll(
        context: Context,
        engine: SkiePolicyEngine,
        onProgress: (index: Int, label: String) -> Unit,
        onComplete: (results: List<StepResult>) -> Unit
    ) {
        Thread {
            val results = mutableListOf<StepResult>()
            ALL_STEPS.forEachIndexed { index, (stepId, label) ->
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    onProgress(index, label)
                }
                val result = applyStep(context, engine, stepId, label)
                results.add(result)
                Log.d(TAG, "Step $stepId → success=${result.success}")
                Thread.sleep(300) // brief pause so progress is visible
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                onComplete(results)
            }
        }.start()
    }

    private fun applyStep(
        context: Context,
        engine: SkiePolicyEngine,
        stepId: String,
        label: String
    ): StepResult {
        return try {
            when (stepId) {
                SkiePolicyEngine.STEP_DNS_FILTERING -> {
                    // DNS is async — we trigger it and assume success (verified in Layer 2)
                    engine.applyDnsFiltering(true)
                    StepResult(stepId, label, true)
                }
                SkiePolicyEngine.STEP_FRP -> {
                    engine.applyFrpPolicy(true)
                    StepResult(stepId, label, true)
                }
                else -> {
                    engine.applySingleHardeningStep(stepId)
                    StepResult(stepId, label, true)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Step $stepId failed: ${e.message}")
            StepResult(stepId, label, false, e.message)
        }
    }
}