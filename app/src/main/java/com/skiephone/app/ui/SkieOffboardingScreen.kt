package com.skiephone.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.skiephone.app.compliance.SkieDehardenRunner
import com.skiephone.app.compliance.SkieDehardenVerifier
import com.skiephone.app.compliance.SkieRestoreAppsRunner
import com.skiephone.app.policy.SkiePolicyEngine
import com.skiephone.app.ui.theme.SkieCard
import com.skiephone.app.ui.theme.SkieColors
import com.skiephone.app.ui.theme.SkieTopBar

/**
 * Offboarding / Uninstall flow.
 * 4 steps: Deharden -> Verify -> Restore All Apps -> Remove Device Owner.
 * Same visual pattern as the Compliance Wizard (progress bar, step labels,
 * pass/fail list). Reachable only via the existing PIN+TOTP gated Admin Home —
 * no additional auth layer except an explicit confirmation before the final
 * irreversible step.
 */
@Composable
fun SkieOffboardingScreen(
    rootModifier: Modifier = Modifier.fillMaxSize(),
    onBack: () -> Unit,
    onFinished: () -> Unit
) {
    val context = LocalContext.current
    val engine = remember { SkiePolicyEngine(context) }

    // 0=intro, 1=dehardening, 2=verifying, 3=restoring apps, 4=confirm-remove-owner,
    // 5=removing-owner, 6=complete
    var stage by remember { mutableStateOf(0) }
    var progressIndex by remember { mutableStateOf(0) }
    var progressTotal by remember { mutableStateOf(1) }
    var progressLabel by remember { mutableStateOf("") }

    var dehardenResults by remember { mutableStateOf<List<SkieDehardenRunner.StepResult>>(emptyList()) }
    var verifyResults by remember { mutableStateOf<List<SkieDehardenVerifier.VerifyResult>>(emptyList()) }
    var restoreResults by remember { mutableStateOf<List<SkieRestoreAppsRunner.AppResult>>(emptyList()) }

    var confirmText by remember { mutableStateOf("") }
    var ownerRemoved by remember { mutableStateOf(false) }
    var ownerRemoveError by remember { mutableStateOf<String?>(null) }

    // Block back button during any active processing stage
    BackHandler(enabled = stage in setOf(1, 2, 3, 5)) { /* swallow */ }

    val totalSteps = com.skiephone.app.compliance.SkieComplianceRunner.ALL_STEPS.size
    val animatedProgress by animateFloatAsState(
        targetValue = if (progressTotal > 0) progressIndex.toFloat() / progressTotal else 0f,
        label = "offboardProgress"
    )

    fun startDeharden() {
        stage = 1
        progressIndex = 0
        progressTotal = totalSteps
        SkieDehardenRunner.runAll(context, engine,
            onProgress = { idx, label -> progressIndex = idx; progressLabel = "Removing: $label" },
            onComplete = { results ->
                dehardenResults = results
                stage = 2
                progressIndex = 0
                progressLabel = "Waiting for changes to settle…"
                // Brief settle delay before verification, mirroring compliance wizard
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    SkieDehardenVerifier.runAll(context,
                        onProgress = { idx, label -> progressIndex = idx; progressLabel = "Verifying: $label" },
                        onComplete = { vResults ->
                            verifyResults = vResults
                            stage = 3
                            progressIndex = 0
                            progressLabel = "Scanning installed apps…"
                            SkieRestoreAppsRunner.runAll(context,
                                onProgress = { idx, total, label ->
                                    progressIndex = idx; progressTotal = total
                                    progressLabel = "Restoring: $label"
                                },
                                onComplete = { rResults ->
                                    restoreResults = rResults
                                    stage = 4
                                }
                            )
                        }
                    )
                }, 5000)
            }
        )
    }

    fun removeDeviceOwner() {
        stage = 5
        Thread {
            val success = try {
                val dpm = context.getSystemService(android.content.Context.DEVICE_POLICY_SERVICE)
                        as android.app.admin.DevicePolicyManager
                dpm.clearDeviceOwnerApp(context.packageName)
                true
            } catch (e: Exception) {
                ownerRemoveError = e.message
                false
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                ownerRemoved = success
                stage = 6
            }
        }.start()
    }

    Box(modifier = rootModifier.background(SkieColors.White)) {
        Column(modifier = Modifier.fillMaxSize()) {
            SkieTopBar(title = "Offboarding", onBack = onBack)
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
            Spacer(Modifier.height(8.dp))
            Text("Remove supervision and prepare to uninstall", fontSize = 14.sp,
                color = SkieColors.Grey, textAlign = TextAlign.Center)
            Spacer(Modifier.height(28.dp))

            when (stage) {
                // ── Intro ─────────────────────────────────────────────────────────
                0 -> {
                    OffboardStepCard("1", "Deharden", "Removes all security restrictions applied during setup.", SkieColors.SkyBlue)
                    Spacer(Modifier.height(8.dp))
                    OffboardStepCard("2", "Verify", "Confirms every restriction was actually removed.", SkieColors.Success)
                    Spacer(Modifier.height(8.dp))
                    OffboardStepCard("3", "Restore All Apps", "Unsuspends and unhides every app on the device.", SkieColors.Warning)
                    Spacer(Modifier.height(8.dp))
                    OffboardStepCard("4", "Remove Device Owner", "Permanently ends supervision of this device.", SkieColors.Danger)
                    Spacer(Modifier.height(24.dp))
                    SkieCard(backgroundColor = SkieColors.DangerBg) {
                        Text("This process is intended for when you no longer wish to supervise " +
                                "this device, or before uninstalling Skiephone. Steps 1–3 can be re-run " +
                                "safely. Step 4 is permanent.",
                            color = SkieColors.Danger, fontSize = 13.sp, lineHeight = 18.sp)
                    }
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = { startDeharden() },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = SkieColors.SkyBlue)
                    ) { Text("Begin Offboarding", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = onBack) {
                        Text("← Back", fontSize = 13.sp, color = SkieColors.Grey)
                    }
                }

                // ── Processing stages 1-3 ────────────────────────────────────────
                1, 2, 3 -> {
                    val stageLabel = when (stage) {
                        1 -> "Step 1: Dehardening device…"
                        2 -> "Step 2: Verifying restrictions removed…"
                        3 -> "Step 3: Restoring all apps…"
                        else -> ""
                    }
                    Text(stageLabel, fontSize = 16.sp, color = SkieColors.Ink, fontWeight = FontWeight.Medium)
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

                // ── Confirm remove device owner ──────────────────────────────────
                4 -> {
                    val dehardenFailed = dehardenResults.count { !it.success }
                    val verifyFailed = verifyResults.count { !it.cleared }
                    val restoreFailed = restoreResults.count { !it.success }

                    Text("Steps 1–3 Complete", fontSize = 18.sp, fontWeight = FontWeight.Bold,
                        color = SkieColors.Success)
                    Spacer(Modifier.height(16.dp))

                    SummaryRow("Restrictions removed", dehardenResults.size - dehardenFailed, dehardenResults.size)
                    SummaryRow("Verified cleared", verifyResults.size - verifyFailed, verifyResults.size)
                    SummaryRow("Apps restored", restoreResults.size - restoreFailed, restoreResults.size)

                    if (dehardenFailed > 0 || verifyFailed > 0 || restoreFailed > 0) {
                        Spacer(Modifier.height(12.dp))
                        Surface(color = SkieColors.DangerBg, shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()) {
                            Text("Some items did not complete fully. You can re-run this process " +
                                    "from the start if needed before removing device owner.",
                                color = SkieColors.Danger, fontSize = 12.sp, lineHeight = 16.sp,
                                modifier = Modifier.padding(12.dp))
                        }
                    }

                    Spacer(Modifier.height(28.dp))
                    Surface(color = SkieColors.DangerBg, shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp)) {
                            Text("⚠ Final Step: Remove Device Owner", fontSize = 15.sp,
                                fontWeight = FontWeight.Bold, color = SkieColors.Danger)
                            Spacer(Modifier.height(8.dp))
                            Text("This device will become completely unsupervised. If given to a " +
                                    "child, they will have full open access with no restrictions. " +
                                    "This action cannot be undone.",
                                color = SkieColors.Danger, fontSize = 13.sp, lineHeight = 18.sp)
                            Spacer(Modifier.height(14.dp))
                            Text("Type REMOVE to confirm:", color = SkieColors.Grey, fontSize = 12.sp)
                            Spacer(Modifier.height(6.dp))
                            OutlinedTextField(
                                value = confirmText,
                                onValueChange = { confirmText = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = SkieColors.Danger,
                                    unfocusedBorderColor = SkieColors.Line,
                                    focusedTextColor = SkieColors.Ink,
                                    unfocusedTextColor = SkieColors.Ink
                                )
                            )
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                    Button(
                        onClick = { removeDeviceOwner() },
                        enabled = confirmText.trim().equals("REMOVE", ignoreCase = false),
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (confirmText.trim() == "REMOVE") SkieColors.Danger else SkieColors.Line
                        )
                    ) { Text("I understand, remove supervision", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = { startDeharden() }) {
                        Text("Re-run steps 1–3", fontSize = 13.sp, color = SkieColors.SkyBlue)
                    }
                }

                // ── Removing owner (brief) ───────────────────────────────────────
                5 -> {
                    CircularProgressIndicator(color = SkieColors.SkyBlue)
                    Spacer(Modifier.height(16.dp))
                    Text("Removing device owner…", fontSize = 14.sp, color = SkieColors.Grey)
                }

                // ── Complete ──────────────────────────────────────────────────────
                6 -> {
                    if (ownerRemoved) {
                        Text("✅", fontSize = 56.sp)
                        Spacer(Modifier.height(16.dp))
                        Text("Offboarding Complete", fontSize = 22.sp, fontWeight = FontWeight.Bold,
                            color = SkieColors.Success)
                        Spacer(Modifier.height(12.dp))
                        Surface(color = SkieColors.White, shape = RoundedCornerShape(12.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, SkieColors.Line),
                            modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(18.dp)) {
                                Text("This device is no longer supervised by Skiephone.",
                                    color = SkieColors.Ink, fontSize = 14.sp, lineHeight = 20.sp)
                                Spacer(Modifier.height(12.dp))
                                Text("To finish removing the app:", color = SkieColors.Grey, fontSize = 13.sp)
                                Spacer(Modifier.height(6.dp))
                                Text("1. Open Settings\n2. Go to Apps\n3. Select SkieOS\n4. Tap Uninstall",
                                    color = SkieColors.Ink, fontSize = 14.sp, lineHeight = 22.sp)
                            }
                        }
                        Spacer(Modifier.height(20.dp))
                        Button(onClick = onFinished,
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = SkieColors.SkyBlue)
                        ) { Text("Done", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
                    } else {
                        Text("⚠", fontSize = 56.sp)
                        Spacer(Modifier.height(16.dp))
                        Text("Could Not Remove Device Owner", fontSize = 18.sp,
                            fontWeight = FontWeight.Bold, color = SkieColors.Danger)
                        Spacer(Modifier.height(8.dp))
                        Text(ownerRemoveError ?: "Unknown error. Please try again.",
                            color = SkieColors.Grey, fontSize = 13.sp, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(20.dp))
                        OutlinedButton(onClick = { stage = 4 },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = SkieColors.DeepNavy)
                        ) { Text("Try Again", fontSize = 15.sp) }
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
        }
    }
}

@Composable
private fun OffboardStepCard(number: String, title: String, desc: String, color: Color) {
    Surface(color = SkieColors.White, shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, SkieColors.Line),
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
                Text(title, color = SkieColors.DeepNavy, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(desc, color = SkieColors.Grey, fontSize = 12.sp, lineHeight = 16.sp)
            }
        }
    }
}

@Composable
private fun SummaryRow(label: String, passed: Int, total: Int) {
    val color = if (passed == total) SkieColors.Success else SkieColors.Warning
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = SkieColors.Grey, fontSize = 14.sp)
        Text("$passed / $total", color = color, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}