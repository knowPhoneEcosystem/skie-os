package com.skiephone.app.ui

import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.skiephone.app.ui.theme.SkiePhoneTheme

class SkieStatusActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val deviceId = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ANDROID_ID
        )

        setContent {
            SkiePhoneTheme {
                SkieStatusScreen(deviceId = deviceId)
            }
        }
    }
}

@Composable
fun SkieStatusScreen(deviceId: String) {
    val qrBitmap = remember(deviceId) {
        try {
            val barcodeEncoder = BarcodeEncoder()
            barcodeEncoder.encodeBitmap(deviceId, BarcodeFormat.QR_CODE, 400, 400)
        } catch (e: Exception) {
            null
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF080808)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "skie",
                style = MaterialTheme.typography.displaySmall,
                color = Color(0xFFFFFFFF),
                letterSpacing = (-1).sp
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Device Setup",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF555555)
            )

            Spacer(modifier = Modifier.height(48.dp))

            qrBitmap?.let {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = Color(0xFFFFFFFF),
                    modifier = Modifier.size(220.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = "Device QR Code",
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = deviceId,
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF333333),
                textAlign = TextAlign.Center,
                letterSpacing = 2.sp
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Scan with Skie Parent app to pair.",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF444444),
                textAlign = TextAlign.Center
            )
        }
    }
}