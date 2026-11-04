package com.skiephone.app.ui.wizard

import android.accounts.AccountManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.integration.android.IntentIntegrator
import com.skiephone.app.admin.SkieDeviceAdminReceiver
import com.skiephone.app.ui.theme.SkieCard
import com.skiephone.app.ui.theme.SkieColors
import com.skiephone.app.ui.theme.SkieStepProgress
import com.skiephone.app.ui.theme.SkieTopBar
import com.skiephone.app.util.SkieLoader

// ─────────────────────────────────────────────────────────────────────────────
// STEP 1: Show Device ID + QR → Link to Parent Dashboard
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun SkieWizardStep1Dashboard(
    rootModifier: Modifier = Modifier.fillMaxSize(),
    onNext: () -> Unit,
    onSkip: () -> Unit
) {
    val context = LocalContext.current
    val deviceId = remember {
        android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        )
    }

    // Generate QR bitmap from deviceId using ZXing BarcodeEncoder
    val qrBitmap = remember(deviceId) {
        try {
            val encoder = com.journeyapps.barcodescanner.BarcodeEncoder()
            val bitmap = encoder.encodeBitmap(
                deviceId,
                com.google.zxing.BarcodeFormat.QR_CODE,
                400, 400
            )
            bitmap.asImageBitmap()
        } catch (e: Exception) {
            null
        }
    }

    Box(modifier = rootModifier.background(SkieColors.White)) {
        Column(modifier = Modifier.fillMaxSize()) {
            SkieTopBar(title = "Setup")
            SkieStepProgress(totalSteps = 4, currentStep = 1)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("Connect to Dashboard", fontSize = 24.sp, fontWeight = FontWeight.Bold,
                    color = SkieColors.DeepNavy)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Scan this QR code from your Skie parent dashboard\nto link this device to your account.",
                    fontSize = 14.sp, color = SkieColors.Grey, textAlign = TextAlign.Center,
                    lineHeight = 20.sp
                )
                Spacer(modifier = Modifier.height(28.dp))

                // QR code display — dashboard scans this to link the device
                if (qrBitmap != null) {
                    Surface(
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, SkieColors.Line),
                        color = SkieColors.White,
                        modifier = Modifier.size(200.dp)
                    ) {
                        androidx.compose.foundation.Image(
                            bitmap = qrBitmap,
                            contentDescription = "Device QR Code",
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp)
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .size(200.dp)
                            .background(SkieColors.SkyTint,
                                androidx.compose.foundation.shape.RoundedCornerShape(16.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("QR unavailable", color = SkieColors.Grey, fontSize = 12.sp)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Device ID shown as text below QR for manual entry fallback
                SkieCard(backgroundColor = SkieColors.SkyTint) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Device ID", fontSize = 11.sp, color = SkieColors.Grey,
                            fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = deviceId,
                            fontSize = 12.sp,
                            color = SkieColors.Ink,
                            fontWeight = FontWeight.Medium,
                            letterSpacing = 0.5.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(28.dp))

                Button(
                    onClick = onNext,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = SkieColors.SkyBlue)
                ) {
                    Text("Done — Continue", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(modifier = Modifier.height(16.dp))
                TextButton(onClick = onSkip) {
                    Text("Skip for now", fontSize = 14.sp, color = SkieColors.Grey)
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// STEP 2: Google Account — mandatory, hard block until verified
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun SkieWizardStep2GoogleAccount(
    rootModifier: Modifier = Modifier.fillMaxSize(),
    onNext: () -> Unit
) {
    val context = LocalContext.current
    var hasGoogleAccount by remember { mutableStateOf(false) }
    var checked by remember { mutableStateOf(false) }

    fun checkGoogleAccount() {
        val am = AccountManager.get(context)
        val accounts = am.getAccountsByType("com.google")
        hasGoogleAccount = accounts.isNotEmpty()
        checked = true
    }

    LaunchedEffect(Unit) { checkGoogleAccount() }

    Box(modifier = rootModifier.background(SkieColors.White)) {
        Column(modifier = Modifier.fillMaxSize()) {
            SkieTopBar(title = "Setup")
            SkieStepProgress(totalSteps = 4, currentStep = 2)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("Add Google Account", fontSize = 24.sp, fontWeight = FontWeight.Bold,
                    color = SkieColors.DeepNavy)
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "A Google account must be added now. After hardening, adding new accounts will be blocked for security.\n\nLog in to the account and return here.",
                    fontSize = 14.sp, color = SkieColors.Grey, textAlign = TextAlign.Center,
                    lineHeight = 20.sp
                )
                Spacer(modifier = Modifier.height(28.dp))

                if (checked) {
                    SkieCard(backgroundColor = if (hasGoogleAccount) SkieColors.SuccessBg else SkieColors.DangerBg) {
                        Text(
                            text = if (hasGoogleAccount)
                                "✓ Google account detected"
                            else
                                "✗ No Google account found",
                            modifier = Modifier.fillMaxWidth(),
                            color = if (hasGoogleAccount) SkieColors.Success else SkieColors.Danger,
                            fontSize = 14.sp, fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center
                        )
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }

                // Open Settings → Accounts
                OutlinedButton(
                    onClick = {
                        context.startActivity(Intent(android.provider.Settings.ACTION_ADD_ACCOUNT).apply {
                            putExtra(android.provider.Settings.EXTRA_ACCOUNT_TYPES, arrayOf("com.google"))
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        })
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = SkieColors.DeepNavy)
                ) {
                    Text("Open Settings → Add Account", fontSize = 14.sp)
                }
                Spacer(modifier = Modifier.height(12.dp))

                OutlinedButton(
                    onClick = { checkGoogleAccount() },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = SkieColors.SkyBlue)
                ) {
                    Text("Check Account Status", fontSize = 14.sp)
                }
                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = { if (hasGoogleAccount) onNext() },
                    enabled = hasGoogleAccount,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (hasGoogleAccount) SkieColors.SkyBlue else SkieColors.Line,
                        contentColor = if (hasGoogleAccount) Color.White else SkieColors.Grey
                    )
                ) {
                    Text("Continue", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}


// ─────────────────────────────────────────────────────────────────────────────
// STEP 4: App Management
// Toggles are LOCAL ONLY until "Save Changes" is tapped.
// Save Changes finds-or-appends each changed app into the array-indexed
// effectiveApps node (legacy dashboard structure: numeric keys, packageName
// as a field), then calls SkiePolicyEngine.enforceOneApp() directly and
// synchronously — no listener round-trip, no writeback to the node this
// screen reads, so no risk of a self-triggering loop. Global whitelist apps
// are pre-seeded as locked-on toggles the user cannot turn off.
// ─────────────────────────────────────────────────────────────────────────────

private val ALWAYS_PROTECTED = setOf("com.android.settings")

data class AppItem(
    val packageName: String,
    val label: String,
    var enabled: Boolean,
    val isGlobalWhitelisted: Boolean = false
)

@Composable
fun SkieWizardStep4AppManagement(
    rootModifier: Modifier = Modifier.fillMaxSize(),
    onComplete: () -> Unit
) {
    val context = LocalContext.current
    val deviceId = remember {
        android.provider.Settings.Secure.getString(
            context.contentResolver, android.provider.Settings.Secure.ANDROID_ID)
    }

    var apps by remember { mutableStateOf<List<AppItem>>(emptyList()) }
    var originalStates by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var loading by remember { mutableStateOf(false) }
    var loadingMessage by remember { mutableStateOf("") }
    var saveError by remember { mutableStateOf<String?>(null) }

    // Load apps: launcher-visible apps + previously toggled-off apps (may be hidden now)
    // + global whitelist apps pre-seeded as locked-on, with reliable label resolution
    // for hidden/suspended apps via MATCH_DISABLED_COMPONENTS | MATCH_UNINSTALLED_PACKAGES.
    fun buildAppList(globalWhitelist: Set<String>) {
        val pm = context.packageManager
        val savedStates = com.skiephone.app.util.SkiePrefManager.getAppToggleStates(context)
        val savedLabels = com.skiephone.app.util.SkiePrefManager.getAppLabels(context)

        val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolved = pm.queryIntentActivities(launcherIntent, 0)
        val launcherPkgs = resolved.mapNotNull { it.activityInfo.packageName }.toSet()

        // Ground truth: every package actually installed on THIS device, including
        // hidden/disabled ones. The global policy record contains entries for every
        // supported model (Samsung, OnePlus, etc.) — without this filter, phantom
        // entries for packages that don't exist on this specific device would show
        // up as unresolvable, disabled-toggle rows.
        val installedFlags = PackageManager.MATCH_DISABLED_COMPONENTS or
                PackageManager.MATCH_UNINSTALLED_PACKAGES
        val allInstalledPkgs = try {
            pm.getInstalledApplications(installedFlags).map { it.packageName }.toSet()
        } catch (e: Exception) {
            emptySet()
        }

        val allPkgs = ((launcherPkgs + savedStates.keys + globalWhitelist) intersect allInstalledPkgs)
            .filter { it != context.packageName }

        val labelFlags = installedFlags

        val newLabelCache = mutableMapOf<String, String>()
        val items = allPkgs.mapNotNull { pkg ->
            val isGlobal = pkg in globalWhitelist
            val label: String = try {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, labelFlags)).toString()
            } catch (e: Exception) {
                // App resolution failed even with disabled/uninstalled flags —
                // fall back to last known cached label so the name never regresses
                // to a raw package string after the app is hidden.
                savedLabels[pkg] ?: pkg
            }
            newLabelCache[pkg] = label
            val enabled: Boolean = if (isGlobal) true else (savedStates[pkg] ?: true)
            AppItem(packageName = pkg, label = label, enabled = enabled, isGlobalWhitelisted = isGlobal)
        }.sortedWith(compareByDescending<AppItem> { it.isGlobalWhitelisted }.thenBy { it.label })

        // Persist freshly resolved labels so they survive future hide/suspend cycles
        com.skiephone.app.util.SkiePrefManager.saveAppLabels(context, savedLabels + newLabelCache)

        apps = items
        originalStates = items.associate { it.packageName to it.enabled }
        loading = false
    }

    // Load apps: launcher-visible apps + previously toggled-off apps (may be hidden now)
    // + global whitelist apps pre-seeded as locked-on, with reliable label resolution
    // for hidden/suspended apps via MATCH_DISABLED_COMPONENTS | MATCH_UNINSTALLED_PACKAGES.
    LaunchedEffect(Unit) {
        loading = true
        loadingMessage = "Loading apps…"
        // Global whitelist — read from RTDB cache (already synced for offline use)
        com.google.firebase.database.FirebaseDatabase.getInstance()
            .getReference("devices/$deviceId/globalPolicyCache")
            .get()
            .addOnSuccessListener { snap ->
                val globalWhitelist = snap.children
                    .mapNotNull { it.getValue(String::class.java) }.toSet()
                buildAppList(globalWhitelist)
            }
            .addOnFailureListener {
                buildAppList(emptySet())
            }
    }

    // Local-only toggle — no RTDB write, no DPM call. Just updates UI state.
    fun toggleApp(item: AppItem, enable: Boolean) {
        if (item.isGlobalWhitelisted) return // locked, cannot be toggled
        apps = apps.map { if (it.packageName == item.packageName) it.copy(enabled = enable) else it }
    }

    fun saveChanges() {
        saveError = null
        val changed = apps.filter { originalStates[it.packageName] != it.enabled }
        if (changed.isEmpty()) {
            com.skiephone.app.policy.SkiePolicyEngine(context).setDeviceManaged(true)
            onComplete()
            return
        }

        loading = true
        loadingMessage = "Saving ${changed.size} app${if (changed.size > 1) "s" else ""}…"

        val db = com.google.firebase.database.FirebaseDatabase.getInstance()
        val effectiveRef = db.getReference("devices/$deviceId/effectiveApps")

        // effectiveApps is array-indexed (legacy dashboard structure): each child key is
        // a numeric index, with packageName stored as a field inside. We must find the
        // existing index for each changed package (or append a new one) rather than
        // writing effectiveApps/{packageName} directly as a key.
        effectiveRef.get()
            .addOnSuccessListener { snapshot ->
                Thread {
                    val failed = mutableListOf<String>()
                    val pkgToIndexKey = mutableMapOf<String, String>()
                    snapshot.children.forEach { child ->
                        val childPkg = child.child("packageName").getValue(String::class.java)
                        if (childPkg != null) pkgToIndexKey[childPkg] = child.key ?: return@forEach
                    }
                    var nextIndex = (snapshot.children.mapNotNull { it.key?.toIntOrNull() }.maxOrNull() ?: -1) + 1

                    changed.forEach { item ->
                        val existingKey = pkgToIndexKey[item.packageName]
                        val key = existingKey ?: nextIndex.toString().also { nextIndex++ }
                        try {
                            // Write/merge the entry — preserves other fields (category, etc.)
                            // if the entry already existed. Always includes appName (the
                            // resolved on-device label) so the dashboard never has to fall
                            // back to displaying a raw package name — the dashboard itself
                            // has no PackageManager access and can't resolve labels on its
                            // own, so this device-side write is the only place that can.
                            effectiveRef.child(key).updateChildren(
                                mapOf(
                                    "packageName" to item.packageName,
                                    "appName" to item.label,
                                    "allowed" to item.enabled,
                                    "category" to "other"
                                )
                            )
                        } catch (e: Exception) {
                            android.util.Log.e("AppMgmt", "write $key failed: ${e.message}")
                        }
                        // Enforce immediately and synchronously — no listener round-trip,
                        // no risk of a self-triggering loop since nothing is written back
                        // to a node this screen itself listens on.
                        val success = com.skiephone.app.policy.SkiePolicyEngine(context)
                            .enforceOneApp(item.packageName, item.enabled)
                        if (!success) failed.add(item.label)
                    }

                    val states = apps.associate { it.packageName to it.enabled }
                    com.skiephone.app.util.SkiePrefManager.saveAppToggleStates(context, states)

                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        originalStates = states
                        loading = false
                        if (failed.isNotEmpty()) {
                            saveError = "Failed to enforce: ${failed.joinToString(", ")}. Please try again."
                        } else {
                            // App policy confirmed and applied — device is now managed.
                            // This flips isDeviceManaged=true in RTDB so that on any
                            // subsequent app start, effectiveApps enforcement runs immediately
                            // rather than waiting for onboarding again.
                            com.skiephone.app.policy.SkiePolicyEngine(context).setDeviceManaged(true)
                            onComplete()
                        }
                    }
                }.start()
            }
            .addOnFailureListener { e ->
                loading = false
                saveError = "Could not read current app list: ${e.message}"
            }
    }

    Box(modifier = rootModifier.background(SkieColors.White)) {
        Column(modifier = Modifier.fillMaxSize()) {
            SkieTopBar(title = "App Management")
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    "Turn off apps you want to hide from the child. Settings and globally " +
                            "required apps are always protected.",
                    fontSize = 14.sp, color = SkieColors.Grey, lineHeight = 20.sp
                )
                if (saveError != null) {
                    Spacer(modifier = Modifier.height(10.dp))
                    SkieCard(backgroundColor = SkieColors.DangerBg) {
                        Text(saveError!!, color = SkieColors.Danger, fontSize = 12.sp)
                    }
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
            ) {
                apps.forEach { app ->
                    val isProtected = app.packageName in ALWAYS_PROTECTED || app.isGlobalWhitelisted
                    SkieCard(modifier = Modifier.padding(vertical = 4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(app.label, color = SkieColors.Ink, fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium)
                                if (app.packageName in ALWAYS_PROTECTED) {
                                    Text("Always protected", color = SkieColors.Grey, fontSize = 12.sp)
                                } else if (app.isGlobalWhitelisted) {
                                    Text("Required — locked on", color = SkieColors.Grey, fontSize = 12.sp)
                                }
                            }
                            Switch(
                                checked = app.enabled,
                                onCheckedChange = { enabled -> toggleApp(app, enabled) },
                                enabled = !isProtected,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = SkieColors.SkyBlue,
                                    uncheckedTrackColor = SkieColors.Line
                                )
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Sticky bottom save bar — Apple Settings pattern for batch edits
            Column(
                modifier = Modifier
                    .background(SkieColors.White)
                    .padding(20.dp)
            ) {
                Button(
                    onClick = { saveChanges() },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = SkieColors.SkyBlue)
                ) {
                    Text("Save Changes", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        if (loading) SkieLoader(loadingMessage)
    }
}