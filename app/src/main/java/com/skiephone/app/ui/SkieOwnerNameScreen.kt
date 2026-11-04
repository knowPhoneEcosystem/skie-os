package com.skiephone.app.ui

import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.database.FirebaseDatabase
import com.skiephone.app.policy.SkiePolicyEngine
import com.skiephone.app.ui.theme.SkieCard
import com.skiephone.app.ui.theme.SkieColors
import com.skiephone.app.ui.theme.SkieTopBar
import com.skiephone.app.util.SkieLoader

/**
 * One-time owner name entry screen — shown after TOTP enrollment during
 * initial provisioning. The name is saved to devices/{deviceId}/ownerName
 * in RTDB and applied via dpm.setOrganizationName() so it replaces the
 * generic "Your organization" string on the lock screen, MDM dialogs, and
 * everywhere Android surfaces the managing organisation name.
 *
 * On re-provisioning (name already exists in RTDB for this deviceId), the
 * field is pre-populated automatically and the Next button is enabled
 * immediately — the parent just taps Next without retyping.
 */
@Composable
fun SkieOwnerNameScreen(
    rootModifier: Modifier = Modifier.fillMaxSize(),
    onComplete: () -> Unit
) {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current

    var name by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val deviceId = remember {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
    }

    // On first composition, check RTDB for an existing owner name (re-provisioning case).
    // If found, pre-fill the field and enable the Next button immediately.
    LaunchedEffect(Unit) {
        FirebaseDatabase.getInstance()
            .getReference("devices/$deviceId/ownerName")
            .get()
            .addOnSuccessListener { snap ->
                val existing = snap.getValue(String::class.java)?.trim()
                if (!existing.isNullOrBlank()) {
                    name = existing
                }
                loading = false
            }
            .addOnFailureListener {
                // Network failure — let the parent type the name manually.
                // This is safe since saveOwnerName() will write it fresh.
                loading = false
            }
    }

    fun save() {
        val trimmed = name.trim()
        if (trimmed.isBlank()) {
            error = "Please enter your name to continue."
            return
        }
        error = null
        saving = true
        keyboardController?.hide()
        try {
            val engine = SkiePolicyEngine(context)
            engine.saveOwnerName(trimmed)
        } catch (e: Exception) {
            // saveOwnerName() handles its own errors internally — proceed regardless
            // so a DPM hiccup doesn't block provisioning permanently.
        }
        saving = false
        onComplete()
    }

    Box(modifier = rootModifier.background(SkieColors.White)) {
        Column(modifier = Modifier.fillMaxSize()) {
            SkieTopBar(title = "About You")

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "👋",
                    fontSize = 48.sp
                )
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    text = "What's your name?",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = SkieColors.DeepNavy,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "This appears on the device lock screen and system\n" +
                            "dialogs instead of \"Your organization\".",
                    fontSize = 14.sp,
                    color = SkieColors.Grey,
                    textAlign = TextAlign.Center,
                    lineHeight = 20.sp
                )
                Spacer(modifier = Modifier.height(32.dp))

                if (loading) {
                    CircularProgressIndicator(
                        color = SkieColors.SkyBlue,
                        modifier = Modifier.size(32.dp)
                    )
                } else {
                    OutlinedTextField(
                        value = name,
                        onValueChange = {
                            name = it
                            error = null
                        },
                        label = { Text("Your name", color = SkieColors.Grey) },
                        placeholder = { Text("e.g. Priya Sharma", color = SkieColors.Grey.copy(alpha = 0.5f)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Words,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(onDone = { save() }),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = SkieColors.SkyBlue,
                            unfocusedBorderColor = SkieColors.Line,
                            focusedTextColor = SkieColors.Ink,
                            unfocusedTextColor = SkieColors.Ink,
                            cursorColor = SkieColors.SkyBlue
                        )
                    )

                    if (error != null) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = error!!,
                            color = SkieColors.Danger,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center
                        )
                    }

                    Spacer(modifier = Modifier.height(28.dp))

                    Button(
                        onClick = { save() },
                        enabled = name.trim().isNotBlank(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SkieColors.SkyBlue,
                            disabledContainerColor = SkieColors.Line
                        )
                    ) {
                        Text(
                            text = "Continue",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (name.trim().isNotBlank()) Color.White else SkieColors.Grey
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Informational note so the parent understands what this is for
                    SkieCard(backgroundColor = SkieColors.SkyTint) {
                        Text(
                            text = "This name is shown to the child on the lock screen " +
                                    "as the device manager. You can change it later from Settings.",
                            fontSize = 12.sp,
                            color = SkieColors.Grey,
                            lineHeight = 17.sp
                        )
                    }
                }
            }
        }

        if (saving) {
            SkieLoader("Saving…")
        }
    }
}