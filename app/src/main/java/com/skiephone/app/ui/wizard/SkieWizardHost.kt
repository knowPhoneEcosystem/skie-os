package com.skiephone.app.ui.wizard

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.skiephone.app.util.SkiePrefManager

/**
 * Wizard host — routes between steps 1–4.
 * Step 1 is skippable. Steps 2, 3, 4 are sequential.
 * On completion, sets wizard done flag in SharedPrefs and calls onComplete.
 */
@Composable
fun SkieWizardHost(
    rootModifier: Modifier = Modifier.fillMaxSize(),
    onComplete: () -> Unit
) {
    val context = LocalContext.current
    var step by remember { mutableStateOf(1) }

    fun finishWizard() {
        SkiePrefManager.setWizardComplete(context)
        onComplete()
    }

    when (step) {
        1 -> SkieWizardStep1Dashboard(
            rootModifier = rootModifier,
            onNext = { step = 2 },
            onSkip = { step = 2 }
        )
        2 -> SkieWizardStep2GoogleAccount(
            rootModifier = rootModifier,
            onNext = { step = 3 }
        )
        3 -> SkieWizardStep3Compliance(
            rootModifier = rootModifier,
            onComplete = { step = 4 }
        )
        4 -> SkieWizardStep4AppManagement(
            rootModifier = rootModifier,
            onComplete = { finishWizard() }
        )
    }
}