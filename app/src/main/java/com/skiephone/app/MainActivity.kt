package com.skiephone.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.skiephone.app.auth.SkieAuthManager
import com.skiephone.app.services.SkiePolicyService
import com.skiephone.app.ui.*
import com.skiephone.app.ui.theme.SkieBottomNav
import com.skiephone.app.ui.theme.SkieNavTab
import com.skiephone.app.ui.wizard.*
import com.skiephone.app.util.SkiePrefManager

/**
 * Entry point.
 *
 * Routing:
 * - Child screen: always shown first, no PIN required
 * - Admin Portal tap → PIN+TOTP every single time, no session persistence
 * - After auth: wizard (first time) or admin home
 * - Admin home: back button returns to child screen
 * - Forgot PIN after 3 wrong attempts: TOTP-verified reset flow
 */
class MainActivity : ComponentActivity() {

    private val versionName: String by lazy {
        try { packageManager.getPackageInfo(packageName, 0).versionName ?: "—" }
        catch (e: Exception) { "—" }
    }

    private val versionCode: Int by lazy {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P)
                packageManager.getPackageInfo(packageName, 0).longVersionCode.toInt()
            else @Suppress("DEPRECATION") packageManager.getPackageInfo(packageName, 0).versionCode
        } catch (e: Exception) { 0 }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!SkiePolicyService.isRunning) {
            startService(Intent(this, SkiePolicyService::class.java))
        }
        setContent {
            SkieApp(versionName = versionName, versionCode = versionCode)
        }
    }
}

private enum class Screen {
    CHILD,
    CHECKING,
    CHECK_FAILED,
    PIN_SETUP,
    TOTP_ENROLL,
    OWNER_NAME,
    PIN_ENTRY,
    WIZARD,
    ADMIN_HOME,
    ADMIN_SETTINGS,
    OFFBOARDING,
    STEP_1, STEP_2, STEP_3, STEP_4
}

@Composable
private fun SkieApp(versionName: String, versionCode: Int) {
    val context = LocalContext.current
    var screen by remember { mutableStateOf(Screen.CHILD) }

    // Apply system bar insets once at the root so every screen is safe
    val rootModifier = Modifier
        .fillMaxSize()
        .safeDrawingPadding()

    var checkStatusMessage by remember { mutableStateOf("Checking…") }

    fun goToAdminPortal() {
        screen = Screen.CHECKING
        checkStatusMessage = "Checking…"
        SkieAuthManager.isPinSetup(
            context,
            onResult = { pinExists ->
                screen = if (!pinExists) Screen.PIN_SETUP else Screen.PIN_ENTRY
            },
            onRetrying = { attempt ->
                checkStatusMessage = "Still checking… (attempt $attempt)"
            },
            onGiveUp = {
                screen = Screen.CHECK_FAILED
            }
        )
    }

    // Force re-authentication whenever the app is backgrounded while on an
    // authenticated/admin screen — PIN+TOTP must always be asked fresh on
    // resume, in case the parent forgets to fully close the app. CHILD,
    // PIN_SETUP, TOTP_ENROLL, WIZARD, and CHECKING are intentionally excluded
    // since they're either pre-auth or mid-setup flows that shouldn't be
    // interrupted by a brief background blip.
    val authenticatedScreens = remember {
        setOf(
            Screen.ADMIN_HOME, Screen.ADMIN_SETTINGS, Screen.OFFBOARDING,
            Screen.STEP_1, Screen.STEP_2, Screen.STEP_3, Screen.STEP_4
        )
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && screen in authenticatedScreens) {
                screen = Screen.CHILD
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Maps the existing Screen enum to a bottom-nav tab for the 4 screens that
    // belong to the persistent admin tab bar. Screens outside this set (auth,
    // wizard, offboarding) render without the bottom nav, since tab navigation
    // doesn't make sense mid-flow for those.
    val tabScreens = remember {
        mapOf(
            Screen.ADMIN_HOME to SkieNavTab.HOME,
            Screen.STEP_4 to SkieNavTab.APPS,
            Screen.STEP_3 to SkieNavTab.COMPLIANCE,
            Screen.ADMIN_SETTINGS to SkieNavTab.SETTINGS
        )
    }
    fun onTabSelect(tab: SkieNavTab) {
        screen = when (tab) {
            SkieNavTab.HOME -> Screen.ADMIN_HOME
            SkieNavTab.APPS -> Screen.STEP_4
            SkieNavTab.COMPLIANCE -> Screen.STEP_3
            SkieNavTab.SETTINGS -> Screen.ADMIN_SETTINGS
        }
    }

    // Wrap any tab screen's content with the persistent bottom nav beneath it.
    @Composable
    fun WithBottomNavIfTab(content: @Composable () -> Unit) {
        val currentTab = tabScreens[screen]
        if (currentTab != null) {
            androidx.compose.foundation.layout.Column(modifier = Modifier.fillMaxSize()) {
                androidx.compose.foundation.layout.Box(modifier = Modifier.weight(1f)) { content() }
                SkieBottomNav(current = currentTab, onSelect = { onTabSelect(it) })
            }
        } else {
            content()
        }
    }

    // Wrap every screen in root modifier via a simple Box-less approach —
    // each screen receives the modifier as a parameter or applies it internally.
    // We handle it here by passing a callback that each screen calls on its root Box.
    WithBottomNavIfTab {
        when (screen) {
            Screen.CHILD -> SkieChildScreen(
                currentVersionName = versionName,
                currentVersionCode = versionCode,
                rootModifier = rootModifier,
                onAdminPortalClick = { goToAdminPortal() }
            )

            Screen.CHECKING -> {
                SkieChildScreen(
                    currentVersionName = versionName,
                    currentVersionCode = versionCode,
                    rootModifier = rootModifier,
                    onAdminPortalClick = {}
                )
                com.skiephone.app.util.SkieLoader(checkStatusMessage)
            }

            Screen.CHECK_FAILED -> SkieCheckFailedScreen(
                rootModifier = rootModifier,
                onRetry = { goToAdminPortal() },
                onCancel = { screen = Screen.CHILD }
            )

            Screen.PIN_SETUP -> SkiePinSetupScreen(
                rootModifier = rootModifier,
                onPinSaved = { screen = Screen.TOTP_ENROLL }
            )

            Screen.TOTP_ENROLL -> SkieTotpEnrollScreen(
                rootModifier = rootModifier,
                onEnrolled = {
                    // After TOTP, always go to owner name entry — even on re-provisioning,
                    // SkieOwnerNameScreen fetches and pre-fills the existing name from RTDB
                    // so the user just taps Next without retyping.
                    screen = Screen.OWNER_NAME
                }
            )

            Screen.OWNER_NAME -> SkieOwnerNameScreen(
                rootModifier = rootModifier,
                onComplete = {
                    screen = if (!SkiePrefManager.isWizardComplete(context))
                        Screen.WIZARD else Screen.ADMIN_HOME
                }
            )

            Screen.PIN_ENTRY -> SkiePinEntryScreen(
                rootModifier = rootModifier,
                onAuthenticated = {
                    screen = Screen.CHECKING
                    checkStatusMessage = "Checking…"
                    SkieAuthManager.isTotpEnrolled(
                        context,
                        onResult = { enrolled ->
                            if (!enrolled) {
                                screen = Screen.TOTP_ENROLL
                                return@isTotpEnrolled
                            }
                            // TOTP confirmed — now check if owner name has been set.
                            // If missing (e.g. existing install before this feature was added,
                            // or a re-provision that skipped the name step), route to OWNER_NAME.
                            val deviceId = android.provider.Settings.Secure.getString(
                                context.contentResolver,
                                android.provider.Settings.Secure.ANDROID_ID
                            )
                            com.google.firebase.database.FirebaseDatabase.getInstance()
                                .getReference("devices/$deviceId/ownerName")
                                .get()
                                .addOnSuccessListener { snap ->
                                    val hasName = snap.exists() &&
                                            snap.getValue(String::class.java)?.isNotBlank() == true
                                    screen = when {
                                        !hasName -> Screen.OWNER_NAME
                                        !SkiePrefManager.isWizardComplete(context) -> Screen.WIZARD
                                        else -> Screen.ADMIN_HOME
                                    }
                                }
                                .addOnFailureListener {
                                    // On failure, proceed without blocking on name check
                                    screen = if (!SkiePrefManager.isWizardComplete(context))
                                        Screen.WIZARD else Screen.ADMIN_HOME
                                }
                        },
                        onRetrying = { attempt ->
                            checkStatusMessage = "Still checking… (attempt $attempt)"
                        },
                        onGiveUp = {
                            screen = Screen.CHECK_FAILED
                        }
                    )
                },
                onBack = { screen = Screen.CHILD }
            )

            Screen.WIZARD -> SkieWizardHost(
                rootModifier = rootModifier,
                onComplete = { screen = Screen.ADMIN_HOME }
            )

            Screen.ADMIN_HOME -> SkieAdminHomeScreen(
                versionName = versionName,
                rootModifier = rootModifier,
                onStepCard = { step ->
                    screen = when (step) {
                        1 -> Screen.STEP_1; 2 -> Screen.STEP_2
                        3 -> Screen.STEP_3; 4 -> Screen.STEP_4
                        else -> Screen.ADMIN_HOME
                    }
                },
                onSettings = { screen = Screen.ADMIN_SETTINGS },
                onBack = { screen = Screen.CHILD },
                onOffboarding = { screen = Screen.OFFBOARDING }
            )

            Screen.ADMIN_SETTINGS -> SkieAdminSettingsScreen(
                rootModifier = rootModifier,
                onBack = { screen = Screen.ADMIN_HOME }
            )

            Screen.OFFBOARDING -> SkieOffboardingScreen(
                rootModifier = rootModifier,
                onBack = { screen = Screen.ADMIN_HOME },
                onFinished = { screen = Screen.CHILD }
            )

            Screen.STEP_1 -> SkieWizardStep1Dashboard(
                rootModifier = rootModifier,
                onNext = { screen = Screen.ADMIN_HOME },
                onSkip = { screen = Screen.ADMIN_HOME }
            )
            Screen.STEP_2 -> SkieWizardStep2GoogleAccount(
                rootModifier = rootModifier,
                onNext = { screen = Screen.ADMIN_HOME }
            )
            Screen.STEP_3 -> SkieWizardStep3Compliance(
                rootModifier = rootModifier,
                onComplete = { screen = Screen.ADMIN_HOME }
            )
            Screen.STEP_4 -> SkieWizardStep4AppManagement(
                rootModifier = rootModifier,
                onComplete = { screen = Screen.ADMIN_HOME }
            )
        }
    }
}