package com.skiephone.app.compliance

import android.content.Context
import android.provider.Settings
import android.util.Log
import com.google.firebase.database.FirebaseDatabase
import com.skiephone.app.policy.SkiePolicyEngine

/**
 * Computes compliance score /100.
 * Manual steps start as pending (not counted as pass OR fail until user confirms).
 * As user confirms each manual step, score updates in real time.
 */
object SkieComplianceScorer {

    private const val TAG = "SkieComplianceScorer"

    data class ComplianceReport(
        val score: Int,
        val totalSteps: Int,
        val passedSteps: Int,
        val failedSteps: List<FailedStep>,
        val manualSteps: List<ManualStep>,
        val timestamp: Long
    )

    data class FailedStep(
        val stepId: String,
        val label: String,
        val failedLayers: List<String>
    )

    data class ManualStep(
        val stepId: String,
        val label: String,
        val instructions: String,
        val warningIfSkipped: String,
        var confirmed: Boolean? = null // null=pending, true=pass, false=fail
    )

    data class ManualStepDef(val instructions: String, val warning: String)

    // Manual step definitions — only steps requiring physical device testing
    val MANUAL_STEP_DEFINITIONS: Map<String, ManualStepDef> by lazy {
        mapOf(
            SkiePolicyEngine.STEP_FRP to ManualStepDef(
                instructions = "Attempt a factory reset via Settings > General Management > Reset > Factory data reset. If it proceeds without asking for your Google account credentials, this step FAILED. If it asks for the registered account before resetting, this step PASSED.",
                warning = "Factory Reset Protection may not be active. A stolen device could be reset without your credentials."
            )
        )
    }

    fun compute(
        layer1: List<SkieComplianceRunner.StepResult>,
        layer2: List<SkieComplianceVerifier.VerifyResult>,
        layer3: List<SkieComplianceAttackSim.AttackResult>,
        manualConfirmations: Map<String, Boolean> = emptyMap()
    ): ComplianceReport {
        val totalSteps = SkieComplianceRunner.ALL_STEPS.size
        val pointsPerStep = 100.0 / totalSteps

        val failedSteps = mutableListOf<FailedStep>()
        val manualSteps = mutableListOf<ManualStep>()

        SkieComplianceRunner.ALL_STEPS.forEach { (stepId, label) ->
            val isManual = stepId in SkieComplianceVerifier.MANUAL_STEPS

            if (isManual) {
                val def = MANUAL_STEP_DEFINITIONS[stepId]
                manualSteps.add(ManualStep(
                    stepId = stepId,
                    label = label,
                    instructions = def?.instructions ?: "Test this manually.",
                    warningIfSkipped = def?.warning ?: "This step has not been verified.",
                    confirmed = manualConfirmations[stepId]
                ))
                // Manual step counts as failed only if user explicitly confirmed fail
                if (manualConfirmations[stepId] == false) {
                    failedSteps.add(FailedStep(stepId, label, listOf("Manual Test")))
                }
            } else {
                val failedLayers = mutableListOf<String>()
                val l1 = layer1.find { it.stepId == stepId }
                val l2 = layer2.find { it.stepId == stepId }
                val l3 = layer3.find { it.stepId == stepId }
                if (l1?.success == false) failedLayers.add("Application")
                if (l2?.effective == false) failedLayers.add("Verification")
                if (l3?.blocked == false) failedLayers.add("Attack Simulation")
                if (failedLayers.isNotEmpty()) {
                    failedSteps.add(FailedStep(stepId, label, failedLayers))
                }
            }
        }

        val passedSteps = totalSteps - failedSteps.size
        val score = (passedSteps * pointsPerStep).toInt().coerceIn(0, 100)

        return ComplianceReport(
            score = score,
            totalSteps = totalSteps,
            passedSteps = passedSteps,
            failedSteps = failedSteps,
            manualSteps = manualSteps,
            timestamp = System.currentTimeMillis()
        )
    }

    fun writeToRtdb(context: Context, report: ComplianceReport) {
        val deviceId = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ANDROID_ID)
        val data = mapOf(
            "score" to report.score,
            "totalSteps" to report.totalSteps,
            "passedSteps" to report.passedSteps,
            "failedCount" to report.failedSteps.size,
            "failedSteps" to report.failedSteps.map { fs ->
                mapOf("stepId" to fs.stepId, "label" to fs.label, "failedLayers" to fs.failedLayers)
            },
            "manualStepsPending" to report.manualSteps.count { it.confirmed == null },
            "timestamp" to report.timestamp
        )
        FirebaseDatabase.getInstance()
            .getReference("devices/$deviceId/complianceScore")
            .setValue(data)
            .addOnSuccessListener { Log.d(TAG, "complianceScore written: ${report.score}/100") }
            .addOnFailureListener { e -> Log.e(TAG, "complianceScore write failed: ${e.message}") }
    }
}