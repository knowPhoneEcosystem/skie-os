package com.skiephone.app.compliance

import android.content.Context
import android.provider.Settings
import android.util.Log
import com.google.firebase.database.FirebaseDatabase
import com.skiephone.app.policy.SkiePolicyEngine

/**
 * Offboarding Step 1: Deharden.
 * Reverses every hardening step applied during the compliance wizard, one at a time,
 * reusing the same step list and labels as SkieComplianceRunner for a consistent UI.
 * Also clears each step's devices/{deviceId}/hardenLog/{stepId} entry on success,
 * mirroring the existing dashboard REMOVE_HARDENING_STEP command's behavior, so the
 * dashboard/RTDB state stays consistent with what was actually undone on-device.
 */
object SkieDehardenRunner {

    private const val TAG = "SkieDehardenRunner"

    data class StepResult(
        val stepId: String,
        val label: String,
        val success: Boolean,
        val error: String? = null
    )

    fun runAll(
        context: Context,
        engine: SkiePolicyEngine,
        onProgress: (index: Int, label: String) -> Unit,
        onComplete: (results: List<StepResult>) -> Unit
    ) {
        Thread {
            val deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            val results = mutableListOf<StepResult>()
            SkieComplianceRunner.ALL_STEPS.forEachIndexed { index, (stepId, label) ->
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    onProgress(index, label)
                }
                val result = removeStep(context, deviceId, engine, stepId, label)
                results.add(result)
                Log.d(TAG, "Deharden step $stepId → success=${result.success}")
                Thread.sleep(300)
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                onComplete(results)
            }
            // Clear stale hardenLog key from the removed bulk-suspension model —
            // no longer corresponds to any active hardening step.
            try {
                FirebaseDatabase.getInstance()
                    .getReference("devices/$deviceId/hardenLog/suspend_non_whitelisted")
                    .removeValue()
            } catch (e: Exception) {
                Log.e(TAG, "stale hardenLog clear failed: ${e.message}")
            }
        }.start()
    }

    private fun removeStep(
        context: Context,
        deviceId: String,
        engine: SkiePolicyEngine,
        stepId: String,
        label: String
    ): StepResult {
        return try {
            engine.removeHardeningStep(stepId)
            // Clear the RTDB hardenLog entry so the dashboard reflects the removed
            // state — same pattern as the existing dashboard-triggered single-step removal.
            try {
                FirebaseDatabase.getInstance()
                    .getReference("devices/$deviceId/hardenLog/$stepId")
                    .removeValue()
            } catch (e: Exception) {
                Log.e(TAG, "hardenLog clear failed for $stepId: ${e.message}")
            }
            StepResult(stepId, label, true)
        } catch (e: Exception) {
            Log.e(TAG, "Deharden step $stepId failed: ${e.message}")
            StepResult(stepId, label, false, e.message)
        }
    }
}