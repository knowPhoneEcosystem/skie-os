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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.database.FirebaseDatabase
import com.skiephone.app.ui.theme.SkieBadge
import com.skiephone.app.ui.theme.SkieCard
import com.skiephone.app.ui.theme.SkieColors
import com.skiephone.app.ui.theme.SkieTopBar
import com.skiephone.app.util.SkieLoader

/**
 * Child-facing screen. No PIN required.
 * Shows Skie branding, current app version, and update+restart actions.
 * "Admin Portal" entry routes to PIN entry — kept as a quiet icon action in
 * the top bar rather than a prominent button, since this screen belongs to
 * the child, not the parent.
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

    Box(modifier = rootModifier.background(SkieColors.White)) {
        Column(modifier = Modifier.fillMaxSize()) {
            SkieTopBar(
                title = "Skiephone",
                trailing = {
                    IconButton(onClick = onAdminPortalClick) {
                        Text("⚙", fontSize = 20.sp, color = SkieColors.Grey)
                    }
                }
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Spacer(modifier = Modifier.height(40.dp))

                // Status card — calm, reassuring, Screen-Time style
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .background(SkieColors.SkyTint, shape = androidx.compose.foundation.shape.CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("🛡", fontSize = 32.sp)
                    }
                    Text(
                        text = "This device is managed",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = SkieColors.DeepNavy,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "Skiephone keeps this device safe and on track.",
                        fontSize = 14.sp,
                        color = SkieColors.Grey,
                        textAlign = TextAlign.Center
                    )

                    if (updateAvailable) {
                        Spacer(modifier = Modifier.height(4.dp))
                        SkieBadge(
                            text = "Update available — v$latestVersionCode",
                            color = SkieColors.SkyBlue,
                            backgroundColor = SkieColors.SkyTint
                        )
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
                                val intent = Intent(Intent.ACTION_VIEW,
                                    android.net.Uri.parse(url)).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                                loading = false
                            },
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = SkieColors.SkyBlue)
                        ) {
                            Text("Update Skiephone", fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                                color = Color.White)
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
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = SkieColors.SkyTint)
                    ) {
                        Text("Restart Device", fontSize = 16.sp, fontWeight = FontWeight.Medium,
                            color = SkieColors.DeepNavy)
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "v$currentVersionName",
                        fontSize = 12.sp,
                        color = SkieColors.Grey,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        if (loading) {
            SkieLoader(loadingMessage)
        }
    }
}