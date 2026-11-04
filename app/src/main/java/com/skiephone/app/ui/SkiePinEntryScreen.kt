package com.skiephone.app.ui

import android.content.Intent
import android.net.Uri
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
import com.skiephone.app.ui.theme.SkieTopBar
import com.skiephone.app.util.SkieLoader

private const val SUPPORT_PHONE = "+918147915885"
private const val SUPPORT_EMAIL = "support@skiephone.com"
private const val SUPPORT_WHATSAPP = "918147915885"

/**
 * PIN + TOTP entry screen for admin portal.
 * Step 1: enter PIN (validated against RTDB).
 * Step 2: enter TOTP code (validated locally).
 * Shows support contact options if locked out.
 */
@Composable
fun SkiePinEntryScreen(
    rootModifier: Modifier = Modifier.fillMaxSize(),
    onAuthenticated: () -> Unit,
    onBack: () -> Unit = {}
) {
    val context = LocalContext.current
    var pin by remember { mutableStateOf("") }
    var totpCode by remember { mutableStateOf("") }
    var step by remember { mutableStateOf(1) } // 1 = PIN, 2 = TOTP
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var showSupport by remember { mutableStateOf(false) }

    fun isOnline(): Boolean {
        val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
                as android.net.ConnectivityManager
        val nc = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return nc.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun submitPin() {
        error = null
        if (!isOnline()) { error = "No internet connection required to validate PIN."; return }
        if (pin.length != 6) { error = "Enter your 6-digit PIN."; return }
        loading = true
        SkieAuthManager.validatePin(context, pin) { valid ->
            loading = false
            when (valid) {
                true -> { step = 2; error = null }
                false -> { error = "Incorrect PIN. Please try again." }
                null -> { error = "Network error. Check your connection." }
            }
        }
    }

    fun submitTotp() {
        error = null
        if (totpCode.length != 6) { error = "Enter the 6-digit code from your authenticator app."; return }
        loading = true
        SkieAuthManager.validateTotp(context, totpCode) { valid ->
            loading = false
            when (valid) {
                true -> onAuthenticated()
                false -> { error = "Code is incorrect or expired. Try the latest code." }
                null -> { error = "Authenticator not set up. Contact support." }
            }
        }
    }

    Box(modifier = rootModifier.background(SkieColors.White)) {
        Column(modifier = Modifier.fillMaxSize()) {
            SkieTopBar(title = if (step == 1) "Admin Portal" else "Two-Factor Auth", onBack = onBack)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = if (step == 1)
                        "Enter your 6-digit admin PIN to continue."
                    else
                        "Enter the code from your authenticator app.",
                    fontSize = 14.sp, color = SkieColors.Grey, textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(32.dp))

                if (step == 1) {
                    OutlinedTextField(
                        value = pin,
                        onValueChange = { if (it.length <= 6 && it.all { c -> c.isDigit() }) pin = it },
                        label = { Text("PIN", color = SkieColors.Grey) },
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
                } else {
                    OutlinedTextField(
                        value = totpCode,
                        onValueChange = { if (it.length <= 6 && it.all { c -> c.isDigit() }) totpCode = it },
                        label = { Text("Authenticator code", color = SkieColors.Grey) },
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
                }

                if (error != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(error!!, color = SkieColors.Danger, fontSize = 13.sp,
                        textAlign = TextAlign.Center)
                }

                Spacer(modifier = Modifier.height(28.dp))

                Button(
                    onClick = { if (step == 1) submitPin() else submitTotp() },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = SkieColors.SkyBlue)
                ) {
                    Text(
                        text = if (step == 1) "Continue" else "Verify",
                        fontSize = 16.sp, fontWeight = FontWeight.SemiBold
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                TextButton(onClick = { showSupport = !showSupport }) {
                    Text(
                        "Forgot PIN or lost authenticator?",
                        fontSize = 13.sp, color = SkieColors.Grey
                    )
                }

                if (showSupport) {
                    Spacer(modifier = Modifier.height(12.dp))
                    SkieCard(backgroundColor = SkieColors.SkyTint) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Contact Skie Support to reset your PIN.",
                                color = SkieColors.Grey, fontSize = 13.sp,
                                textAlign = TextAlign.Center)

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        context.startActivity(Intent(Intent.ACTION_DIAL,
                                            Uri.parse("tel:$SUPPORT_PHONE")).apply {
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                                    },
                                    modifier = Modifier.fillMaxWidth().height(48.dp),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = SkieColors.DeepNavy)
                                ) {
                                    Text("📞 Call Support", fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium)
                                }
                            }
                            Row {
                                OutlinedButton(
                                    onClick = {
                                        val uri = Uri.parse("https://wa.me/$SUPPORT_WHATSAPP")
                                        context.startActivity(Intent(Intent.ACTION_VIEW, uri).apply {
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                                    },
                                    modifier = Modifier.fillMaxWidth().height(48.dp),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = SkieColors.DeepNavy)
                                ) {
                                    Text("💬 WhatsApp Support", fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium)
                                }
                            }
                            Row {
                                OutlinedButton(
                                    onClick = {
                                        val intent = Intent(Intent.ACTION_SENDTO).apply {
                                            data = Uri.parse("mailto:$SUPPORT_EMAIL")
                                            putExtra(Intent.EXTRA_SUBJECT, "Admin PIN Reset Request")
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                        context.startActivity(intent)
                                    },
                                    modifier = Modifier.fillMaxWidth().height(48.dp),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = SkieColors.DeepNavy)
                                ) {
                                    Text("✉️ Email Support", fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium)
                                }
                            }
                        }
                    }
                }
            }
        }

        if (loading) SkieLoader(if (step == 1) "Validating PIN…" else "Verifying code…")
    }
}