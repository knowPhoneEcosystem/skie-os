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
import androidx.compose.ui.graphics.Brush
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

private const val SUPPORT_PHONE    = "+918147915885"
private const val SUPPORT_EMAIL    = "support@skiephone.com"
private const val SUPPORT_WHATSAPP = "918147915885"
private const val MAX_PIN_ATTEMPTS = 3

/**
 * PIN + TOTP entry screen. Called on every admin portal open — no session persistence.
 *
 * Flow:
 * Step 1 — enter 6-digit PIN (RTDB validated)
 * Step 2 — enter TOTP code (local validated)
 *
 * After MAX_PIN_ATTEMPTS wrong PINs → show "Forgot PIN?" option which opens
 * a TOTP-verified PIN reset flow (enter TOTP → set new PIN → save to RTDB).
 */
@Composable
fun SkiePinEntryScreen(
    rootModifier: Modifier = Modifier.fillMaxSize(),
    onAuthenticated: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current

    var step         by remember { mutableStateOf(1) } // 1=PIN, 2=TOTP, 3=ForgotPINtotp, 4=NewPIN
    var pin          by remember { mutableStateOf("") }
    var totpCode     by remember { mutableStateOf("") }
    var newPin       by remember { mutableStateOf("") }
    var confirmNewPin by remember { mutableStateOf("") }
    var resetTotpCode by remember { mutableStateOf("") }
    var error        by remember { mutableStateOf<String?>(null) }
    var loading      by remember { mutableStateOf(false) }
    var loadingMsg   by remember { mutableStateOf("") }
    var pinAttempts  by remember { mutableStateOf(0) }
    var showSupport  by remember { mutableStateOf(false) }

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
        loading = true; loadingMsg = "Validating PIN…"
        SkieAuthManager.validatePin(context, pin) { valid ->
            loading = false
            when (valid) {
                true -> { step = 2; error = null; pin = "" }
                false -> {
                    pinAttempts++
                    error = if (pinAttempts >= MAX_PIN_ATTEMPTS)
                        "Too many wrong attempts."
                    else
                        "Incorrect PIN. ${MAX_PIN_ATTEMPTS - pinAttempts} attempt(s) left."
                    pin = ""
                }
                null -> error = "Network error. Check your connection."
            }
        }
    }

    fun submitTotp() {
        error = null
        if (totpCode.length != 6) { error = "Enter the 6-digit code from your authenticator app."; return }
        loading = true; loadingMsg = "Verifying code…"
        SkieAuthManager.validateTotp(context, totpCode) { valid ->
            loading = false
            when (valid) {
                true -> onAuthenticated()
                false -> { error = "Code is incorrect or expired. Try the latest code."; totpCode = "" }
                null -> { error = "Authenticator not set up. Contact support." }
            }
        }
    }

    // Step 3: verify TOTP before allowing PIN reset
    fun submitResetTotp() {
        error = null
        if (resetTotpCode.length != 6) { error = "Enter the 6-digit code from your authenticator app."; return }
        loading = true; loadingMsg = "Verifying…"
        SkieAuthManager.validateTotp(context, resetTotpCode) { valid ->
            loading = false
            when (valid) {
                true -> { step = 4; error = null; resetTotpCode = "" }
                false -> { error = "Code is incorrect or expired."; resetTotpCode = "" }
                null -> error = "Authenticator not set up. Contact support."
            }
        }
    }

    // Step 4: save new PIN
    fun submitNewPin() {
        error = null
        if (newPin.length != 6) { error = "PIN must be exactly 6 digits."; return }
        if (newPin != confirmNewPin) { error = "PINs do not match."; return }
        if (!isOnline()) { error = "No internet connection."; return }
        loading = true; loadingMsg = "Saving new PIN…"
        SkieAuthManager.savePin(context, newPin,
            onSuccess = {
                loading = false
                // Reset flow complete — go back to fresh PIN entry
                step = 1; pinAttempts = 0; newPin = ""; confirmNewPin = ""
                error = null
            },
            onFailure = { msg -> loading = false; error = msg }
        )
    }

    Box(modifier = rootModifier.background(
        Brush.verticalGradient(listOf(Color(0xFF0A0A0F), Color(0xFF0D1117)))
    )) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            when (step) {
                // ── Step 1: PIN ───────────────────────────────────────────────
                1 -> {
                    Text("Admin Portal", fontSize = 28.sp, fontWeight = FontWeight.Bold,
                        color = Color.White)
                    Spacer(Modifier.height(8.dp))
                    Text("Enter your 6-digit admin PIN to continue.",
                        fontSize = 14.sp, color = Color(0xFF8E8E93), textAlign = TextAlign.Center)
                    Spacer(Modifier.height(40.dp))

                    OutlinedTextField(
                        value = pin,
                        onValueChange = { if (it.length <= 6 && it.all { c -> c.isDigit() }) pin = it },
                        label = { Text("PIN", color = Color(0xFF8E8E93)) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = pinFieldColors()
                    )

                    ErrorText(error)
                    Spacer(Modifier.height(24.dp))

                    Button(onClick = { submitPin() },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0A84FF))
                    ) { Text("Continue", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }

                    Spacer(Modifier.height(16.dp))

                    // Show forgot PIN after max attempts
                    if (pinAttempts >= MAX_PIN_ATTEMPTS) {
                        TextButton(onClick = { step = 3; error = null }) {
                            Text("Forgot PIN? Reset via Authenticator",
                                fontSize = 13.sp, color = Color(0xFFFF9F0A))
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { showSupport = !showSupport }) {
                        Text("Need help? Contact support",
                            fontSize = 13.sp, color = Color(0xFF636366))
                    }

                    if (showSupport) SupportCard(context)

                    Spacer(Modifier.height(16.dp))
                    TextButton(onClick = onBack) {
                        Text("← Back", fontSize = 13.sp, color = Color(0xFF636366))
                    }
                }

                // ── Step 2: TOTP ──────────────────────────────────────────────
                2 -> {
                    Text("Two-Factor Auth", fontSize = 28.sp, fontWeight = FontWeight.Bold,
                        color = Color.White)
                    Spacer(Modifier.height(8.dp))
                    Text("Enter the code from your authenticator app.",
                        fontSize = 14.sp, color = Color(0xFF8E8E93), textAlign = TextAlign.Center)
                    Spacer(Modifier.height(40.dp))

                    OutlinedTextField(
                        value = totpCode,
                        onValueChange = { if (it.length <= 6 && it.all { c -> c.isDigit() }) totpCode = it },
                        label = { Text("Authenticator code", color = Color(0xFF8E8E93)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = pinFieldColors()
                    )

                    ErrorText(error)
                    Spacer(Modifier.height(24.dp))

                    Button(onClick = { submitTotp() },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0A84FF))
                    ) { Text("Verify", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }

                    Spacer(Modifier.height(16.dp))
                    TextButton(onClick = { step = 1; error = null }) {
                        Text("← Back to PIN", fontSize = 13.sp, color = Color(0xFF636366))
                    }
                }

                // ── Step 3: Forgot PIN — verify TOTP first ────────────────────
                3 -> {
                    Text("Reset PIN", fontSize = 28.sp, fontWeight = FontWeight.Bold,
                        color = Color.White)
                    Spacer(Modifier.height(8.dp))
                    Text("Verify your identity using the authenticator app before setting a new PIN.",
                        fontSize = 14.sp, color = Color(0xFF8E8E93), textAlign = TextAlign.Center,
                        lineHeight = 20.sp)
                    Spacer(Modifier.height(40.dp))

                    OutlinedTextField(
                        value = resetTotpCode,
                        onValueChange = { if (it.length <= 6 && it.all { c -> c.isDigit() }) resetTotpCode = it },
                        label = { Text("Authenticator code", color = Color(0xFF8E8E93)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = pinFieldColors()
                    )

                    ErrorText(error)
                    Spacer(Modifier.height(24.dp))

                    Button(onClick = { submitResetTotp() },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9F0A))
                    ) { Text("Verify Identity", fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                        color = Color.Black) }

                    Spacer(Modifier.height(16.dp))
                    TextButton(onClick = { step = 1; error = null }) {
                        Text("← Cancel", fontSize = 13.sp, color = Color(0xFF636366))
                    }
                }

                // ── Step 4: Set new PIN ───────────────────────────────────────
                4 -> {
                    Text("New PIN", fontSize = 28.sp, fontWeight = FontWeight.Bold,
                        color = Color.White)
                    Spacer(Modifier.height(8.dp))
                    Text("Identity verified. Set your new 6-digit PIN.",
                        fontSize = 14.sp, color = Color(0xFF8E8E93), textAlign = TextAlign.Center)
                    Spacer(Modifier.height(40.dp))

                    OutlinedTextField(
                        value = newPin,
                        onValueChange = { if (it.length <= 6 && it.all { c -> c.isDigit() }) newPin = it },
                        label = { Text("New PIN", color = Color(0xFF8E8E93)) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = pinFieldColors()
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = confirmNewPin,
                        onValueChange = { if (it.length <= 6 && it.all { c -> c.isDigit() }) confirmNewPin = it },
                        label = { Text("Confirm new PIN", color = Color(0xFF8E8E93)) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = pinFieldColors()
                    )

                    ErrorText(error)
                    Spacer(Modifier.height(24.dp))

                    Button(onClick = { submitNewPin() },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0A84FF))
                    ) { Text("Save New PIN", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
                }
            }
        }

        if (loading) SkieLoader(loadingMsg)
    }
}

@Composable
private fun ErrorText(error: String?) {
    if (error != null) {
        Spacer(Modifier.height(12.dp))
        Text(error, color = Color(0xFFFF453A), fontSize = 13.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun pinFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Color(0xFF0A84FF),
    unfocusedBorderColor = Color(0xFF3A3A3C),
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    cursorColor = Color(0xFF0A84FF)
)

@Composable
private fun SupportCard(context: android.content.Context) {
    Spacer(Modifier.height(12.dp))
    Surface(color = Color(0xFF1C1C1E), shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Contact Skie Support to reset your PIN.",
                color = Color(0xFF8E8E93), fontSize = 13.sp, textAlign = TextAlign.Center)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    context.startActivity(Intent(Intent.ACTION_DIAL,
                        Uri.parse("tel:$SUPPORT_PHONE")).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                }, shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                ) { Text("📞 Call", fontSize = 13.sp) }
                OutlinedButton(onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://wa.me/$SUPPORT_WHATSAPP")).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                }, shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                ) { Text("💬 WhatsApp", fontSize = 13.sp) }
                OutlinedButton(onClick = {
                    context.startActivity(Intent(Intent.ACTION_SENDTO).apply {
                        data = Uri.parse("mailto:$SUPPORT_EMAIL")
                        putExtra(Intent.EXTRA_SUBJECT, "Admin PIN Reset Request")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                }, shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                ) { Text("✉️ Email", fontSize = 13.sp) }
            }
        }
    }
}