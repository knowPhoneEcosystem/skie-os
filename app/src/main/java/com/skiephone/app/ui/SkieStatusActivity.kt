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
import androidx.compose.ui.graphics.asImageBitmap
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

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Skie Device",
                fontSize = 28.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Text(
                text = "Device ID",
                fontSize = 14.sp,
                modifier = Modifier.padding(bottom = 4.dp)
            )

            Text(
                text = deviceId,
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 32.dp)
            )

            qrBitmap?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = "Device QR Code",
                    modifier = Modifier.size(250.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Scan this QR code with the Skie Parent app to pair this device.",
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 16.dp)
            )
        }
    }
}