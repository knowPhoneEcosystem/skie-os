package com.skiephone.app.ui.wizard

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.skiephone.app.compliance.*
import com.skiephone.app.policy.SkiePolicyEngine
import com.skiephone.app.ui.theme.SkieCard
import com.skiephone.app.ui.theme.SkieColors
import com.skiephone.app.ui.theme.SkieStepProgress
import com.skiephone.app.ui.theme.SkieTopBar
import com.skiephone.app.util.SkieLoader

@Composable
fun SkieWizardStep3Compliance(
    rootModifier: Modifier = Modifier.fillMaxSize(),
    onComplete: () -> Unit
) {
    val context = LocalContext.current
    val engine = remember { SkiePolicyEngine(context) }

    var substep by remember { mutableStateOf(0) }
    // 0=idle, 1=L1, 2=L2, 3=L3, 4=automated done, 5=manual verification, 6=final score
    var progressIndex by remember { mutableStateOf(0) }
    var progressLabel by remember { mutableStateOf("") }
    var layer1Results by remember { mutableStateOf<List<SkieComplianceRunner.StepResult>>(emptyList()) }
    var layer2Results by remember { mutableStateOf<List<SkieComplianceVerifier.VerifyResult>>(emptyList()) }
    var layer3Results by remember { mutableStateOf<List<SkieComplianceAttackSim.AttackResult>>(emptyList()) }
    var report by remember { mutableStateOf<SkieComplianceScorer.ComplianceReport?>(null) }
    var loading by remember { mutableStateOf(false) }
    var waitingForPropagation by remember { mutableStateOf(false) }
    // Manual step confirmations: stepId -> true (pass) / false (fail) / null (pending)
    var manualConfirmations by remember { mutableStateOf<Map<String, Boolean?>>(emptyMap()) }
    var showSkipWarning by remember { mutableStateOf(false) }

    // Block back button during active compliance run — substeps 1-5
    BackHandler(enabled = substep in 1..5) { /* swallow — do not allow back during compliance */ }

    val totalSteps = SkieComplianceRunner.ALL_STEPS.size
    val animatedProgress by animateFloatAsState(
        targetValue = if (totalSteps > 0) progressIndex.toFloat() / totalSteps else 0f,
        label = "progress"
    )

    // Recompute report whenever manual confirmations change
    val currentReport = remember(manualConfirmations, layer1Results, layer2Results, layer3Results) {
        if (layer1Results.isEmpty()) null
        else {
            val confirmed = manualConfirmations.filterValues { it != null }
                .mapValues { it.value!! }
            SkieComplianceScorer.compute(layer1Results, layer2Results, layer3Results, confirmed)
        }
    }

    val allManualConfirmed = currentReport?.manualSteps?.all { it.confirmed != null } == true
    val pendingManualCount = currentReport?.manualSteps?.count { it.confirmed == null } ?: 0

    var runLayer3: () -> Unit = {}
    var runLayer2: () -> Unit = {}
    var runLayer1: () -> Unit = {}

    runLayer3 = {
        loading = false; progressIndex = 0
        SkieComplianceAttackSim.runAll(context,
            onProgress = { idx, label -> progressIndex = idx; progressLabel = "Testing: $label" },
            onComplete = { results ->
                layer3Results = results
                substep = 4 // automated done, move to manual verification
            })
    }

    runLayer2 = {
        loading = false; progressIndex = 0
        SkieComplianceVerifier.runAll(context,
            onProgress = { idx, label -> progressIndex = idx; progressLabel = "Checking: $label" },
            onComplete = { results ->
                layer2Results = results
                substep = 3; runLayer3()
            })
    }

    runLayer1 = {
        substep = 1; loading = false; progressIndex = 0
        SkieComplianceRunner.runAll(context, engine,
            onProgress = { idx, label -> progressIndex = idx; progressLabel = "Applying: $label" },
            onComplete = { results ->
                layer1Results = results
                substep = 2
                // 2s delay — allow OS to propagate hardening before verification
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    runLayer2()
                }, 10000)
            })
    }

    fun reRun() {
        report = null; layer1Results = emptyList(); layer2Results = emptyList()
        layer3Results = emptyList(); progressIndex = 0; progressLabel = ""
        manualConfirmations = emptyMap()
        runLayer1()
    }

    fun finishWithScore() {
        val finalReport = currentReport ?: return
        SkieComplianceScorer.writeToRtdb(context, finalReport)
        report = finalReport
        substep = 6
    }

    Box(modifier = rootModifier.background(SkieColors.White)) {
        Column(modifier = Modifier.fillMaxSize()) {
            SkieTopBar(title = "Setup")
            SkieStepProgress(totalSteps = 4, currentStep = 3)
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
            Spacer(Modifier.height(8.dp))
            Text("Device Compliance", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = SkieColors.DeepNavy)
            Text("3-layer security audit", fontSize = 14.sp, color = SkieColors.Grey)
            Spacer(Modifier.height(28.dp))

            when {
                // ── Idle ──────────────────────────────────────────────────────────
                substep == 0 -> {
                    SubstepCard("1", "Policy Application", "Applies all hardening steps.", SkieColors.SkyBlue)
                    Spacer(Modifier.height(8.dp))
                    SubstepCard("2", "Effective State Verification", "Checks if Android enforced each step.", SkieColors.Success)
                    Spacer(Modifier.height(8.dp))
                    SubstepCard("3", "Attack Simulation", "Attempts to bypass each hardening step.", SkieColors.Warning)
                    Spacer(Modifier.height(8.dp))
                    SubstepCard("4", "Manual Verification",
                        "${SkieComplianceVerifier.MANUAL_STEPS.size} step${if (SkieComplianceVerifier.MANUAL_STEPS.size != 1) "s" else ""} require physical device testing.",
                        SkieColors.Coral)
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = { runLayer1() },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = SkieColors.SkyBlue)
                    ) { Text("Secure and Comply", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
                    Spacer(Modifier.height(16.dp))
                    Text("Or run individual steps:", fontSize = 12.sp, color = SkieColors.Grey)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { layer1Results = emptyList(); layer2Results = emptyList(); layer3Results = emptyList(); report = null; manualConfirmations = emptyMap(); substep = 1; runLayer1() },
                            modifier = Modifier.weight(1f).height(44.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = SkieColors.SkyBlue)
                        ) { Text("Harden", fontSize = 13.sp) }
                        OutlinedButton(
                            onClick = { layer2Results = emptyList(); layer3Results = emptyList(); report = null; manualConfirmations = emptyMap(); substep = 2; runLayer2() },
                            modifier = Modifier.weight(1f).height(44.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = SkieColors.Success)
                        ) { Text("Verify", fontSize = 13.sp) }
                        OutlinedButton(
                            onClick = { layer3Results = emptyList(); report = null; manualConfirmations = emptyMap(); substep = 3; runLayer3() },
                            modifier = Modifier.weight(1f).height(44.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = SkieColors.Warning)
                        ) { Text("Simulate", fontSize = 13.sp) }
                    }
                }

                // ── Running layers ────────────────────────────────────────────────
                substep in 1..3 -> {
                    val layerLabel = when (substep) {
                        1 -> "Layer 1: Applying hardening…"
                        2 -> "Layer 2: Verifying effective state…"
                        3 -> "Layer 3: Simulating attacks…"
                        else -> ""
                    }
                    Text(layerLabel, fontSize = 16.sp, color = SkieColors.Ink, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(24.dp))
                    LinearProgressIndicator(
                        progress = { animatedProgress },
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                        color = SkieColors.SkyBlue, trackColor = SkieColors.Line
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(progressLabel, fontSize = 13.sp, color = SkieColors.Grey, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    Text("Do not press back or close the screen until complete.",
                        fontSize = 12.sp, color = SkieColors.Danger, textAlign = TextAlign.Center)
                }

                // ── Manual verification ───────────────────────────────────────────
                substep == 4 || substep == 5 -> {
                    val r = currentReport ?: return@Column

                    // Automated results summary
                    val autoFailed = r.failedSteps.filter { fs ->
                        fs.stepId !in SkieComplianceVerifier.MANUAL_STEPS
                    }
                    if (autoFailed.isNotEmpty()) {
                        Text("Automated Issues", fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                            color = SkieColors.Danger)
                        Spacer(Modifier.height(8.dp))
                        autoFailed.forEach { fs ->
                            Surface(color = SkieColors.DangerBg, shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(fs.label, color = SkieColors.Ink, fontWeight = FontWeight.Medium,
                                        fontSize = 14.sp)
                                    Text("Failed: ${fs.failedLayers.joinToString(", ")}",
                                        color = SkieColors.Danger, fontSize = 12.sp)
                                }
                            }
                        }
                        Spacer(Modifier.height(20.dp))
                    }

                    // Manual verification section
                    Surface(color = SkieColors.SkyTint, shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("⚠ Manual Verification Required", fontSize = 15.sp,
                                fontWeight = FontWeight.Bold, color = SkieColors.Coral)
                            Spacer(Modifier.height(6.dp))
                            Text("${r.manualSteps.size} step${if (r.manualSteps.size != 1) "s" else ""} cannot be auto-tested and require physical verification on the device. " +
                                    "Complete each test and mark pass or fail. Your score updates in real time.",
                                fontSize = 13.sp, color = SkieColors.Grey, lineHeight = 18.sp)
                        }
                    }
                    Spacer(Modifier.height(16.dp))

                    r.manualSteps.forEach { step ->
                        val confirmed = manualConfirmations[step.stepId]
                        ManualStepCard(
                            step = step,
                            confirmed = confirmed,
                            onPass = {
                                manualConfirmations = manualConfirmations.toMutableMap()
                                    .also { it[step.stepId] = true }
                            },
                            onFail = {
                                manualConfirmations = manualConfirmations.toMutableMap()
                                    .also { it[step.stepId] = false }
                            }
                        )
                        Spacer(Modifier.height(12.dp))
                    }

                    // Live score preview
                    Spacer(Modifier.height(8.dp))
                    val scoreColor = when {
                        r.score >= 80 -> SkieColors.Success
                        r.score >= 50 -> SkieColors.Warning
                        else -> SkieColors.Danger
                    }
                    Surface(color = SkieColors.White, shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Current Score", color = SkieColors.Grey, fontSize = 14.sp)
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text("${r.score}", fontSize = 36.sp, fontWeight = FontWeight.Bold,
                                    color = scoreColor)
                                Text("/100", fontSize = 14.sp, color = SkieColors.Grey,
                                    modifier = Modifier.padding(bottom = 4.dp))
                            }
                        }
                    }
                    if (pendingManualCount > 0) {
                        Spacer(Modifier.height(4.dp))
                        Text("$pendingManualCount manual test(s) still pending",
                            fontSize = 12.sp, color = SkieColors.Warning, textAlign = TextAlign.Center)
                    }

                    Spacer(Modifier.height(20.dp))

                    // Continue button — only enabled when all manual tests confirmed
                    Button(
                        onClick = { finishWithScore() },
                        enabled = allManualConfirmed,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (allManualConfirmed) SkieColors.SkyBlue else SkieColors.Line
                        )
                    ) { Text("Continue", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }

                    Spacer(Modifier.height(12.dp))

                    // Skip manual testing
                    OutlinedButton(
                        onClick = { showSkipWarning = true },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = SkieColors.Grey)
                    ) { Text("Skip manual testing", fontSize = 14.sp) }
                }

                // ── Final score ───────────────────────────────────────────────────
                substep == 6 && report != null -> {
                    val r = report!!
                    val scoreColor = when {
                        r.score >= 80 -> SkieColors.Success
                        r.score >= 50 -> SkieColors.Warning
                        else -> SkieColors.Danger
                    }
                    Text("${r.score}", fontSize = 80.sp, fontWeight = FontWeight.Bold, color = scoreColor)
                    Text("/100", fontSize = 20.sp, color = SkieColors.Grey)
                    Text("${r.passedSteps}/${r.totalSteps} steps passed",
                        fontSize = 14.sp, color = SkieColors.Grey)
                    Spacer(Modifier.height(24.dp))

                    if (r.failedSteps.isNotEmpty()) {
                        Text("Issues Found", fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                            color = SkieColors.Danger)
                        Spacer(Modifier.height(12.dp))
                        r.failedSteps.forEach { fs ->
                            Surface(color = SkieColors.White, shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Column(Modifier.padding(14.dp)) {
                                    Text(fs.label, color = SkieColors.Ink, fontWeight = FontWeight.Medium,
                                        fontSize = 14.sp)
                                    Text("Failed: ${fs.failedLayers.joinToString(", ")}",
                                        color = SkieColors.Danger, fontSize = 12.sp)
                                }
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                    }

                    // Independent action buttons
                    Text("Run Independently", fontSize = 13.sp, color = SkieColors.Grey)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = {
                                layer1Results = emptyList(); layer2Results = emptyList()
                                layer3Results = emptyList(); report = null
                                manualConfirmations = emptyMap()
                                substep = 1; runLayer1()
                            },
                            modifier = Modifier.weight(1f).height(44.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = SkieColors.SkyBlue)
                        ) { Text("Harden", fontSize = 13.sp) }
                        OutlinedButton(
                            onClick = {
                                layer2Results = emptyList(); layer3Results = emptyList()
                                report = null; manualConfirmations = emptyMap()
                                substep = 2; runLayer2()
                            },
                            modifier = Modifier.weight(1f).height(44.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = SkieColors.Success)
                        ) { Text("Verify", fontSize = 13.sp) }
                        OutlinedButton(
                            onClick = {
                                layer3Results = emptyList(); report = null
                                manualConfirmations = emptyMap()
                                substep = 3; runLayer3()
                            },
                            modifier = Modifier.weight(1f).height(44.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = SkieColors.Warning)
                        ) { Text("Simulate", fontSize = 13.sp) }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { reRun() },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = SkieColors.DeepNavy)
                    ) { Text("Run Full Suite Again", fontSize = 15.sp) }
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onComplete,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = SkieColors.SkyBlue)
                    ) { Text("Continue", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
        }

        // No full-screen loader during compliance — progress shown inline in substep 1..3
        // Loader only shown for finishWithScore RTDB write (brief)
        if (loading) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SkieColors.White.copy(alpha = 0.85f))
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(color = SkieColors.SkyBlue,
                        strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                    Text("Saving score…", color = SkieColors.Ink, fontSize = 14.sp)
                }
            }
        }

        // Skip warning dialog
        if (showSkipWarning) {
            val r = currentReport
            val pendingSteps = r?.manualSteps?.filter { it.confirmed == null } ?: emptyList()
            AlertDialog(
                onDismissRequest = { showSkipWarning = false },
                containerColor = SkieColors.White,
                title = {
                    Text("Skip Manual Testing?", color = SkieColors.Ink, fontWeight = FontWeight.Bold,
                        fontSize = 18.sp)
                },
                text = {
                    Column {
                        Text("The following steps have NOT been manually verified:",
                            color = SkieColors.Warning, fontSize = 14.sp)
                        Spacer(Modifier.height(12.dp))
                        pendingSteps.forEach { step ->
                            val def = SkieComplianceScorer.MANUAL_STEP_DEFINITIONS[step.stepId]
                            Surface(color = SkieColors.Line, shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(step.label, color = SkieColors.Ink, fontWeight = FontWeight.Medium,
                                        fontSize = 13.sp)
                                    Spacer(Modifier.height(4.dp))
                                    Text("⚠ ${def?.warning ?: "This step has not been verified."}",
                                        color = SkieColors.Warning, fontSize = 12.sp, lineHeight = 16.sp)
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Text("These risks may affect device security. Proceeding means you accept these untested items.",
                            color = SkieColors.Grey, fontSize = 13.sp, lineHeight = 18.sp)
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { showSkipWarning = false; finishWithScore() },
                        colors = ButtonDefaults.buttonColors(containerColor = SkieColors.Danger)
                    ) { Text("I understand, proceed anyway", fontSize = 13.sp) }
                },
                dismissButton = {
                    TextButton(onClick = { showSkipWarning = false }) {
                        Text("Go back and test", color = SkieColors.SkyBlue, fontSize = 13.sp)
                    }
                }
            )
        }
    }
}

@Composable
private fun ManualStepCard(
    step: SkieComplianceScorer.ManualStep,
    confirmed: Boolean?,
    onPass: () -> Unit,
    onFail: () -> Unit
) {
    val borderColor = when (confirmed) {
        true -> SkieColors.Success
        false -> SkieColors.Danger
        null -> SkieColors.Line
    }
    Surface(
        color = SkieColors.White,
        shape = RoundedCornerShape(12.dp),
        border = ButtonDefaults.outlinedButtonBorder,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(step.label, color = SkieColors.Ink, fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp, modifier = Modifier.weight(1f))
                when (confirmed) {
                    true -> Text("✅ Pass", color = SkieColors.Success, fontSize = 13.sp,
                        fontWeight = FontWeight.Medium)
                    false -> Text("❌ Fail", color = SkieColors.Danger, fontSize = 13.sp,
                        fontWeight = FontWeight.Medium)
                    null -> Text("Pending", color = SkieColors.Grey, fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(10.dp))
            Surface(color = SkieColors.SkyTint, shape = RoundedCornerShape(8.dp)) {
                Text(
                    text = "🔍 How to test: ${step.instructions}",
                    modifier = Modifier.padding(12.dp),
                    color = SkieColors.Grey, fontSize = 12.sp, lineHeight = 17.sp
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onPass,
                    modifier = Modifier.weight(1f).height(40.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (confirmed == true) SkieColors.Success else SkieColors.Line
                    )
                ) { Text("✅ Pass", fontSize = 13.sp,
                    color = if (confirmed == true) Color.White else SkieColors.Ink) }
                Button(
                    onClick = onFail,
                    modifier = Modifier.weight(1f).height(40.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (confirmed == false) SkieColors.Danger else SkieColors.Line
                    )
                ) { Text("❌ Fail", fontSize = 13.sp,
                    color = if (confirmed == false) Color.White else SkieColors.Ink) }
            }
        }
    }
}

@Composable
private fun SubstepCard(number: String, title: String, desc: String, color: Color) {
    Surface(color = SkieColors.White, shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = color.copy(alpha = 0.15f), shape = RoundedCornerShape(8.dp),
                modifier = Modifier.size(36.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Text(number, color = color, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(title, color = SkieColors.Ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(desc, color = SkieColors.Grey, fontSize = 12.sp, lineHeight = 16.sp)
            }
        }
    }
}