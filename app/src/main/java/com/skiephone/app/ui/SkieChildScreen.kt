package com.skiephone.app.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.database.FirebaseDatabase
import com.skiephone.app.util.SkieLoader

/**
 * Child-facing screen. No PIN required.
 * Shows Skie branding, current app version, and update+restart actions.
 * "Admin Portal" button routes to PIN entry.
 */
@Composable
fun SkieChildScreen(
    currentVersionName: String,
    currentVersionCode: Int,
    rootModifier: Modifier = Modifier.fillMaxSize(),
    onAdminPortalClick: () -> Unit
) {
    val context = LocalContext.current
    var loading by remember { mutableStateOf(false) }
    var loadingMessage by remember { mutableStateOf("") }
    var latestVersionCode by remember { mutableStateOf<Int?>(null) }
    var latestApkUrl by remember { mutableStateOf<String?>(null) }
    var updateChecked by remember { mutableStateOf(false) }

    // Check for update on load
    LaunchedEffect(Unit) {
        FirebaseDatabase.getInstance()
            .getReference("appConfig/dpcVersion")
            .get()
            .addOnSuccessListener { snap ->
                latestVersionCode = snap.child("versionCode").getValue(Long::class.java)?.toInt()
                latestApkUrl = snap.child("apkUrl").getValue(String::class.java)
                updateChecked = true
            }
            .addOnFailureListener {
                updateChecked = true
            }
    }

    val updateAvailable = updateChecked &&
            latestVersionCode != null &&
            latestVersionCode!! > currentVersionCode

    Box(
        modifier = rootModifier
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF0A0A0F), Color(0xFF0D1117))
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top spacer
            Spacer(modifier = Modifier.height(60.dp))

            // Branding
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "SkieOS",
                    fontSize = 48.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    letterSpacing = (-1).sp
                )
                Text(
                    text = "Managed Device",
                    fontSize = 16.sp,
                    color = Color(0xFF8E8E93),
                    fontWeight = FontWeight.Normal
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "v$currentVersionName",
                    fontSize = 13.sp,
                    color = Color(0xFF636366),
                )

                if (updateAvailable) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Surface(
                        color = Color(0xFF1C1C1E),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            text = "Update available (v${latestVersionCode})",
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            fontSize = 12.sp,
                            color = Color(0xFFFF9F0A)
                        )
                    }
                }
            }

            // Action buttons
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (updateAvailable && latestApkUrl != null) {
                    Button(
                        onClick = {
                            val url = latestApkUrl ?: return@Button
                            loading = true
                            loadingMessage = "Downloading update…"
                            // Trigger OTA via SkieUpdateManager — open URL in browser as fallback
                            val intent = Intent(Intent.ACTION_VIEW,
                                android.net.Uri.parse(url)).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                            loading = false
                        },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFFF9F0A)
                        )
                    ) {
                        Text("Update SkieOS", fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                            color = Color.Black)
                    }
                }

                Button(
                    onClick = {
                        loading = true
                        loadingMessage = "Restarting device…"
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            try {
                                val intent = Intent(context,
                                    com.skiephone.app.services.SkieRestartService::class.java)
                                context.startForegroundService(intent)
                            } catch (e: Exception) {
                                loading = false
                            }
                        }, 500)
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1C1C1E))
                ) {
                    Text("Restart Device", fontSize = 16.sp, fontWeight = FontWeight.Medium,
                        color = Color.White)
                }

                Spacer(modifier = Modifier.height(8.dp))

                TextButton(
                    onClick = onAdminPortalClick,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Admin Portal",
                        fontSize = 14.sp,
                        color = Color(0xFF636366),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        if (loading) {
            SkieLoader(loadingMessage)
        }
    }
}