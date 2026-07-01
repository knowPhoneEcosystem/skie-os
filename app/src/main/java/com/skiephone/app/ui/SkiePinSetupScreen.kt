package com.skiephone.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.skiephone.app.auth.SkieAuthManager
import com.skiephone.app.ui.theme.SkieColors
import com.skiephone.app.ui.theme.SkieTopBar
import com.skiephone.app.util.SkieLoader

/**
 * First-time PIN setup screen.
 * Only shown when no PIN exists in RTDB.
 * Requires internet — shows error if offline.
 * On success → routes to TOTP enrollment.
 */
@Composable
fun SkiePinSetupScreen(
    rootModifier: Modifier = Modifier.fillMaxSize(),
    onPinSaved: () -> Unit
) {
    val context = LocalContext.current
    var pin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    // Check internet before allowing any action
    fun isOnline(): Boolean {
        val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
                as android.net.ConnectivityManager
        val nc = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return nc.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun submit() {
        error = null
        if (!isOnline()) {
            error = "No internet connection. Please connect and try again."
            return
        }
        if (pin.length != 6) {
            error = "PIN must be exactly 6 digits."
            return
        }
        if (pin != confirmPin) {
            error = "PINs do not match."
            return
        }
        loading = true
        SkieAuthManager.savePin(
            context = context,
            pin = pin,
            onSuccess = {
                loading = false
                onPinSaved()
            },
            onFailure = { msg ->
                loading = false
                error = msg
            }
        )
    }

    Box(modifier = rootModifier.background(SkieColors.White)) {
        Column(modifier = Modifier.fillMaxSize()) {
            SkieTopBar(title = "Set Admin PIN")
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
            Text(
                "Create a 6-digit PIN to protect the Admin Portal.\nOnly the parent should know this PIN.",
                fontSize = 14.sp, color = SkieColors.Grey, textAlign = TextAlign.Center,
                lineHeight = 20.sp
            )
            Spacer(modifier = Modifier.height(32.dp))

            OutlinedTextField(
                value = pin,
                onValueChange = { if (it.length <= 6 && it.all { c -> c.isDigit() }) pin = it },
                label = { Text("6-digit PIN", color = SkieColors.Grey) },
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
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(
                value = confirmPin,
                onValueChange = { if (it.length <= 6 && it.all { c -> c.isDigit() }) confirmPin = it },
                label = { Text("Confirm PIN", color = SkieColors.Grey) },
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

            if (error != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(error!!, color = SkieColors.Danger, fontSize = 13.sp,
                    textAlign = TextAlign.Center)
            }

            Spacer(modifier = Modifier.height(28.dp))

            Button(
                onClick = { submit() },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = SkieColors.SkyBlue)
            ) {
                Text("Set PIN", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            }
        }

        if (loading) SkieLoader("Saving PIN…")
    }
}