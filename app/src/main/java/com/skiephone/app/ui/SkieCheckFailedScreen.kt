package com.skiephone.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Shown when SkieAuthManager could not get a confirmed answer from Firebase
 * after exhausting its retry-with-backoff schedule (~45s). This screen is
 * reached ONLY after every retry attempt failed — it never appears for a
 * single transient blip, since those are silently retried automatically.
 *
 * No guess is ever made about whether a PIN exists — the user must restore
 * connectivity and retry, or cancel back to the child screen.
 */
@Composable
fun SkieCheckFailedScreen(
    rootModifier: Modifier = Modifier.fillMaxSize(),
    onRetry: () -> Unit,
    onCancel: () -> Unit
) {
    Box(modifier = rootModifier.background(Color(0xFF0A0A0F))) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("⚠", fontSize = 48.sp)
            Spacer(Modifier.height(16.dp))
            Text("Can't Reach Server", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.height(10.dp))
            Text(
                "We couldn't verify your admin PIN status after several attempts. " +
                        "This usually means the device has no internet connection right now.",
                fontSize = 14.sp, color = Color(0xFF8E8E93), textAlign = TextAlign.Center, lineHeight = 20.sp
            )

            Spacer(Modifier.height(28.dp))
            Surface(color = Color(0xFF1C1C1E), shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp)) {
                    Text("Try this:", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Spacer(Modifier.height(10.dp))
                    TroubleshootStep("1", "Turn Wi-Fi off, then back on")
                    TroubleshootStep("2", "Turn Mobile Data off, then back on")
                    TroubleshootStep("3", "Check Airplane Mode is switched off")
                    TroubleshootStep("4", "Move closer to your Wi-Fi router, or to an area with better signal")
                    TroubleshootStep("5", "Wait a few seconds, then tap Retry below")
                }
            }

            Spacer(Modifier.height(28.dp))
            Button(
                onClick = onRetry,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0A84FF))
            ) {
                Text("Retry", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onCancel) {
                Text("Cancel", fontSize = 14.sp, color = Color(0xFF636366))
            }
        }
    }
}

@Composable
private fun TroubleshootStep(number: String, text: String) {
    Row(modifier = Modifier.padding(vertical = 4.dp)) {
        Text("$number.", color = Color(0xFF0A84FF), fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
            modifier = Modifier.padding(end = 8.dp))
        Text(text, color = Color(0xFF8E8E93), fontSize = 13.sp, lineHeight = 18.sp)
    }
}