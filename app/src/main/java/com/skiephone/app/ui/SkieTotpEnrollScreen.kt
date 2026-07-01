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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.skiephone.app.auth.SkieAuthManager
import com.skiephone.app.auth.SkieTotpManager
import com.skiephone.app.ui.theme.SkieColors
import com.skiephone.app.ui.theme.SkieTopBar
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

    Box(modifier = rootModifier.background(SkieColors.White)) {
        Column(modifier = Modifier.fillMaxSize()) {
            SkieTopBar(title = "Set Up Authenticator")
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
            Text(
                "Scan this QR code with Google Authenticator or Microsoft Authenticator.\nThen enter the 6-digit code to confirm.",
                fontSize = 14.sp, color = SkieColors.Grey, textAlign = TextAlign.Center,
                lineHeight = 20.sp
            )
            Spacer(modifier = Modifier.height(28.dp))

            // QR Code
            if (qrBitmap != null) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = SkieColors.White,
                    border = androidx.compose.foundation.BorderStroke(1.dp, SkieColors.Line),
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
                    color = SkieColors.Warning, fontSize = 12.sp, textAlign = TextAlign.Center)
            }

            Spacer(modifier = Modifier.height(32.dp))

            OutlinedTextField(
                value = verifyCode,
                onValueChange = { if (it.length <= 6 && it.all { c -> c.isDigit() }) verifyCode = it },
                label = { Text("Enter code from authenticator", color = SkieColors.Grey) },
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
                onClick = { verify() },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = SkieColors.SkyBlue)
            ) {
                Text("Confirm & Continue", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            }
        }

        if (loading) SkieLoader("Saving authenticator setup…")
    }
}