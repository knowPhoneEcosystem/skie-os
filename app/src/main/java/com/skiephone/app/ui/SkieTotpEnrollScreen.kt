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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.skiephone.app.auth.SkieAuthManager
import com.skiephone.app.auth.SkieTotpManager
import com.skiephone.app.util.SkieLoader

/**
 * TOTP enrollment screen.
 * Generates a new secret, displays QR code, parent scans with authenticator app,
 * then must enter a valid 6-digit code to confirm enrollment.
 * Only proceeds after verification succeeds.
 */
@Composable
fun SkieTotpEnrollScreen(
    rootModifier: Modifier = Modifier.fillMaxSize(),
    onEnrolled: () -> Unit
) {
    val context = LocalContext.current
    val deviceId = remember { SkieAuthManager.getDeviceId(context) }
    val secret = remember { SkieTotpManager.generateSecret() }
    val otpUri = remember { SkieTotpManager.buildOtpAuthUri(secret, deviceId) }

    var verifyCode by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    // Generate QR bitmap
    val qrBitmap = remember {
        try {
            BarcodeEncoder().encodeBitmap(otpUri, BarcodeFormat.QR_CODE, 400, 400)
        } catch (e: Exception) { null }
    }

    fun verify() {
        error = null
        if (verifyCode.length != 6) {
            error = "Enter the 6-digit code from your authenticator app."
            return
        }
        val valid = SkieTotpManager.verify(secret, verifyCode)
        if (!valid) {
            error = "Code is incorrect or expired. Try the latest code."
            return
        }
        loading = true
        SkieAuthManager.saveTotpSecret(
            context = context,
            secret = secret,
            onSuccess = {
                loading = false
                onEnrolled()
            },
            onFailure = { msg ->
                loading = false
                error = "Failed to save: $msg"
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
            Text("Set Up Authenticator", fontSize = 26.sp, fontWeight = FontWeight.Bold,
                color = Color.White)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Scan this QR code with Google Authenticator or Microsoft Authenticator.\nThen enter the 6-digit code to confirm.",
                fontSize = 14.sp, color = Color(0xFF8E8E93), textAlign = TextAlign.Center,
                lineHeight = 20.sp
            )
            Spacer(modifier = Modifier.height(32.dp))

            // QR Code
            if (qrBitmap != null) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color.White,
                    modifier = Modifier.size(200.dp)
                ) {
                    AndroidView(
                        factory = { ctx ->
                            android.widget.ImageView(ctx).apply {
                                setImageBitmap(qrBitmap)
                                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                            }
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp)
                    )
                }
            } else {
                Text("QR generation failed. Manual secret: $secret",
                    color = Color(0xFFFF9F0A), fontSize = 12.sp, textAlign = TextAlign.Center)
            }

            Spacer(modifier = Modifier.height(32.dp))

            OutlinedTextField(
                value = verifyCode,
                onValueChange = { if (it.length <= 6 && it.all { c -> c.isDigit() }) verifyCode = it },
                label = { Text("Enter code from authenticator", color = Color(0xFF8E8E93)) },
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
                onClick = { verify() },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0A84FF))
            ) {
                Text("Confirm & Continue", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        if (loading) SkieLoader("Saving authenticator setup…")
    }
}