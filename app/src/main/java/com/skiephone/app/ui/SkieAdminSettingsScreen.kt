package com.skiephone.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.skiephone.app.auth.SkieAuthManager
import com.skiephone.app.ui.theme.SkieCard
import com.skiephone.app.ui.theme.SkieColors
import com.skiephone.app.ui.theme.SkieSectionHeader
import com.skiephone.app.ui.theme.SkieTopBar
import com.skiephone.app.util.SkieLoader

/**
 * Admin settings: change PIN and re-enroll MFA (re-show QR for new authenticator scan).
 * Only accessible from admin home — already authenticated.
 */
@Composable
fun SkieAdminSettingsScreen(
    rootModifier: Modifier = Modifier.fillMaxSize(),
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var loading by remember { mutableStateOf(false) }
    var loadingMsg by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var success by remember { mutableStateOf<String?>(null) }

    // Change PIN state
    var currentPin by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var confirmNewPin by remember { mutableStateOf("") }

    // Re-enroll MFA
    var showReenroll by remember { mutableStateOf(false) }

    fun changePin() {
        error = null; success = null
        if (currentPin.length != 6 || newPin.length != 6) {
            error = "Both PINs must be 6 digits."; return
        }
        if (newPin != confirmNewPin) {
            error = "New PINs do not match."; return
        }
        loading = true; loadingMsg = "Changing PIN…"
        SkieAuthManager.changePin(
            context, currentPin, newPin,
            onSuccess = {
                loading = false
                success = "PIN changed successfully."
                currentPin = ""; newPin = ""; confirmNewPin = ""
            },
            onFailure = { msg -> loading = false; error = msg }
        )
    }

    Box(modifier = rootModifier.background(SkieColors.White)) {
        Column(modifier = Modifier.fillMaxSize()) {
            SkieTopBar(title = "Settings", onBack = onBack)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {

            SkieSectionHeader("Change PIN")

            listOf(
                "Current PIN" to currentPin to { v: String -> currentPin = v },
                "New PIN" to newPin to { v: String -> newPin = v },
                "Confirm New PIN" to confirmNewPin to { v: String -> confirmNewPin = v }
            ).forEach { (labelAndVal, setter) ->
                val (label, value) = labelAndVal
                OutlinedTextField(
                    value = value,
                    onValueChange = { if (it.length <= 6 && it.all { c -> c.isDigit() }) setter(it) },
                    label = { Text(label, color = SkieColors.Grey) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = SkieColors.SkyBlue,
                        unfocusedBorderColor = SkieColors.Line,
                        focusedTextColor = SkieColors.Ink,
                        unfocusedTextColor = SkieColors.Ink,
                        cursorColor = SkieColors.SkyBlue
                    )
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            if (error != null) {
                Text(error!!, color = SkieColors.Danger, fontSize = 13.sp,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(8.dp))
            }
            if (success != null) {
                Text(success!!, color = SkieColors.Success, fontSize = 13.sp,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(8.dp))
            }

            Button(
                onClick = { changePin() },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = SkieColors.SkyBlue)
            ) {
                Text("Change PIN", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }

            Spacer(modifier = Modifier.height(32.dp))
            Divider(color = SkieColors.Line)
            Spacer(modifier = Modifier.height(24.dp))

            // Re-enroll MFA
            SkieSectionHeader("Authenticator App")
            Text(
                "Lost access to your authenticator app? Re-scan the QR code to set it up again on a new device.",
                fontSize = 13.sp, color = SkieColors.Grey, lineHeight = 18.sp
            )
            Spacer(modifier = Modifier.height(12.dp))

            if (showReenroll) {
                SkieTotpEnrollScreen(
                    onEnrolled = {
                        showReenroll = false
                        success = "Authenticator re-enrolled successfully."
                    }
                )
            } else {
                OutlinedButton(
                    onClick = { showReenroll = true },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = SkieColors.SkyBlue)
                ) {
                    Text("Re-enroll Authenticator", fontSize = 14.sp)
                }
            }
            }
        }

        if (loading) SkieLoader(loadingMsg)
    }
}