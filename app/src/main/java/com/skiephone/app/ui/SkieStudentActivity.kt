package com.skiephone.app.ui

import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.skiephone.app.BuildConfig
import com.skiephone.app.ui.theme.SkiePhoneTheme

class SkieStudentActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val deviceId = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ANDROID_ID
        )
        setContent {
            SkiePhoneTheme {
                SkieStudentScreen(
                    deviceId = deviceId,
                    onBack = { finish() }
                )
            }
        }
    }
}

@Composable
fun SkieStudentScreen(deviceId: String, onBack: () -> Unit) {
    var showQr by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFFE8F4FD), Color(0xFFF5F0FF))
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            // ── Top bar ───────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                // Back arrow
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF1A1A2E).copy(alpha = 0.06f))
                        .clickable { onBack() },
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = "←", fontSize = 16.sp, color = Color(0xFF1A1A2E))
                }

                Text(
                    text = "skie student",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color(0xFF1A1A2E),
                    modifier = Modifier.align(Alignment.Center)
                )

                // QR icon
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF1A1A2E).copy(alpha = 0.06f))
                        .clickable { showQr = true },
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = "⊞", fontSize = 16.sp, color = Color(0xFF1A1A2E))
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Good things take focus. 🌱",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF8A8AAA),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp)
            )

            Spacer(modifier = Modifier.height(28.dp))

            // ── Cards ─────────────────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {

                // Focus Stars
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    color = Color(0xFF1A1A2E)
                ) {
                    Row(
                        modifier = Modifier.padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = "Focus Stars",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color(0xFF8A8AAA)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "—",
                                style = MaterialTheme.typography.headlineMedium,
                                color = Color(0xFFFFFFFF)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Complete quests to earn stars",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF555577)
                            )
                        }
                        Text(text = "⭐", fontSize = 36.sp)
                    }
                }

                // Today's Quests
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    color = Color(0xFFFFFFFF).copy(alpha = 0.7f)
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Today's Quests",
                                style = MaterialTheme.typography.titleSmall,
                                color = Color(0xFF1A1A2E)
                            )
                            Text(
                                text = "0 / 0",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color(0xFF8A8AAA)
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "No quests yet.\nYour parent or teacher will assign quests here.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF8A8AAA),
                            lineHeight = 20.sp
                        )
                    }
                }

                // Streak
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    color = Color(0xFFFFFFFF).copy(alpha = 0.7f)
                ) {
                    Row(
                        modifier = Modifier.padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = "Current Streak",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color(0xFF8A8AAA)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "— days",
                                style = MaterialTheme.typography.titleMedium,
                                color = Color(0xFF1A1A2E)
                            )
                        }
                        Text(text = "🔥", fontSize = 28.sp)
                    }
                }

                // Tip of the day
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    color = Color(0xFFFFFFFF).copy(alpha = 0.7f)
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            text = "Tip of the day",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color(0xFF8A8AAA)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "\"Small steps every day lead to big changes over time.\"",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFF1A1A2E),
                            lineHeight = 22.sp
                        )
                    }
                }

                // Did you know
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    color = Color(0xFFFFFFFF).copy(alpha = 0.7f)
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            text = "Did you know? 🧠",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color(0xFF8A8AAA)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "The human brain can form new habits in as little as 21 days of consistent practice.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFF1A1A2E),
                            lineHeight = 22.sp
                        )
                    }
                }

                // Focus reminder
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    color = Color(0xFFFFFFFF).copy(alpha = 0.7f)
                ) {
                    Row(
                        modifier = Modifier.padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Stay focused.\nYou've got this today.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFF1A1A2E),
                            lineHeight = 22.sp
                        )
                        Text(text = "💪", fontSize = 28.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // ── Version ───────────────────────────────────────────
            Text(
                text = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFFBBBBCC),
                modifier = Modifier
                    .padding(bottom = 16.dp)
                    .navigationBarsPadding()
            )
        }

        // ── QR overlay ────────────────────────────────────────────
        if (showQr) {
            StudentQrOverlay(deviceId = deviceId, onDismiss = { showQr = false })
        }
    }
}

@Composable
fun StudentQrOverlay(deviceId: String, onDismiss: () -> Unit) {
    val qrBitmap = remember(deviceId) {
        try {
            BarcodeEncoder().encodeBitmap(deviceId, BarcodeFormat.QR_CODE, 400, 400)
        } catch (e: Exception) { null }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF080808).copy(alpha = 0.95f))
                .clickable { onDismiss() },
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(32.dp)
            ) {
                Text(
                    text = "skie",
                    style = MaterialTheme.typography.displaySmall,
                    color = Color(0xFFFFFFFF)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Scan to pair this device",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF555555)
                )
                Spacer(modifier = Modifier.height(40.dp))
                qrBitmap?.let {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = Color(0xFFFFFFFF),
                        modifier = Modifier.size(240.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Image(
                                bitmap = it.asImageBitmap(),
                                contentDescription = "Device QR",
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = deviceId,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF888888),
                    textAlign = TextAlign.Center,
                    letterSpacing = 2.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF333333)
                )
                Spacer(modifier = Modifier.height(40.dp))
                Text(
                    text = "Tap anywhere to close",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF444444)
                )
            }
        }
    }
}