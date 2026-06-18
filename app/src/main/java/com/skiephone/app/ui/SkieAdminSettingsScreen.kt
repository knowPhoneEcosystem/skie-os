package com.skiephone.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
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

    Box(
        modifier = rootModifier
            .background(Color(0xFF0A0A0F))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back",
                        tint = Color.White)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text("Admin Settings", fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    color = Color.White)
            }
            Spacer(modifier = Modifier.height(32.dp))

            // Change PIN section
            Text("Change PIN", fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                color = Color(0xFF8E8E93))
            Spacer(modifier = Modifier.height(12.dp))

            listOf(
                "Current PIN" to currentPin to { v: String -> currentPin = v },
                "New PIN" to newPin to { v: String -> newPin = v },
                "Confirm New PIN" to confirmNewPin to { v: String -> confirmNewPin = v }
            ).forEach { (labelAndVal, setter) ->
                val (label, value) = labelAndVal
                OutlinedTextField(
                    value = value,
                    onValueChange = { if (it.length <= 6 && it.all { c -> c.isDigit() }) setter(it) },
                    label = { Text(label, color = Color(0xFF8E8E93)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF0A84FF),
                        unfocusedBorderColor = Color(0xFF3A3A3C),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = Color(0xFF0A84FF)
                    )
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            if (error != null) {
                Text(error!!, color = Color(0xFFFF453A), fontSize = 13.sp,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(8.dp))
            }
            if (success != null) {
                Text(success!!, color = Color(0xFF30D158), fontSize = 13.sp,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(8.dp))
            }

            Button(
                onClick = { changePin() },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0A84FF))
            ) {
                Text("Change PIN", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }

            Spacer(modifier = Modifier.height(32.dp))
            Divider(color = Color(0xFF2C2C2E))
            Spacer(modifier = Modifier.height(24.dp))

            // Re-enroll MFA
            Text("Authenticator App", fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                color = Color(0xFF8E8E93))
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Lost access to your authenticator app? Re-scan the QR code to set it up again on a new device.",
                fontSize = 13.sp, color = Color(0xFF636366), lineHeight = 18.sp
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
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF0A84FF))
                ) {
                    Text("Re-enroll Authenticator", fontSize = 14.sp)
                }
            }
        }

        if (loading) SkieLoader(loadingMsg)
    }
}