package com.skiephone.app.ui

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

    Box(
        modifier = rootModifier
            .background(Brush.verticalGradient(listOf(Color(0xFF0A0A0F), Color(0xFF0D1117))))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("Set Admin PIN", fontSize = 28.sp, fontWeight = FontWeight.Bold,
                color = Color.White)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Create a 6-digit PIN to protect the Admin Portal.\nOnly the parent should know this PIN.",
                fontSize = 14.sp, color = Color(0xFF8E8E93), textAlign = TextAlign.Center,
                lineHeight = 20.sp
            )
            Spacer(modifier = Modifier.height(40.dp))

            OutlinedTextField(
                value = pin,
                onValueChange = { if (it.length <= 6 && it.all { c -> c.isDigit() }) pin = it },
                label = { Text("6-digit PIN", color = Color(0xFF8E8E93)) },
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
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(
                value = confirmPin,
                onValueChange = { if (it.length <= 6 && it.all { c -> c.isDigit() }) confirmPin = it },
                label = { Text("Confirm PIN", color = Color(0xFF8E8E93)) },
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

            if (error != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(error!!, color = Color(0xFFFF453A), fontSize = 13.sp,
                    textAlign = TextAlign.Center)
            }

            Spacer(modifier = Modifier.height(28.dp))

            Button(
                onClick = { submit() },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0A84FF))
            ) {
                Text("Set PIN", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        if (loading) SkieLoader("Saving PIN…")
    }
}