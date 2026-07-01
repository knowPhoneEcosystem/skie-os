package com.skiephone.app.ui

import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.skiephone.app.ui.theme.SkieCard
import com.skiephone.app.ui.theme.SkieColors
import com.skiephone.app.ui.theme.SkieSectionHeader
import com.skiephone.app.ui.theme.SkieTopBar
import java.text.SimpleDateFormat
import java.util.*

/**
 * Parent admin home screen — the "Home" tab content within the admin portal's
 * bottom navigation. Shows compliance score (RTDB, single source of truth),
 * setup step cards, and the danger zone. Accessible only after PIN + TOTP auth.
 *
 * Note: onBack here returns to the child screen (only reachable from the Home
 * tab, since that's the natural "exit admin portal" gesture — the other tabs
 * don't need their own exit, the bottom nav handles cross-tab movement).
 */
@Composable
fun SkieAdminHomeScreen(
    versionName: String,
    rootModifier: Modifier = Modifier.fillMaxSize(),
    onStepCard: (step: Int) -> Unit,
    onSettings: () -> Unit,
    onBack: () -> Unit,
    onOffboarding: () -> Unit
) {
    val context = LocalContext.current
    val deviceId = remember {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
    }

    var complianceScore by remember { mutableStateOf<Int?>(null) }
    var complianceTimestamp by remember { mutableStateOf<Long?>(null) }
    var refreshing by remember { mutableStateOf(false) }

    // Listen to complianceScore from RTDB — realtime, always fresh
    DisposableEffect(Unit) {
        val ref = FirebaseDatabase.getInstance()
            .getReference("devices/$deviceId/complianceScore")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                complianceScore = snapshot.child("score").getValue(Long::class.java)?.toInt()
                complianceTimestamp = snapshot.child("timestamp").getValue(Long::class.java)
                refreshing = false
            }
            override fun onCancelled(error: DatabaseError) { refreshing = false }
        }
        ref.addValueEventListener(listener)
        onDispose { ref.removeEventListener(listener) }
    }

    val scoreColor = when {
        complianceScore == null -> SkieColors.Grey
        complianceScore!! >= 80 -> SkieColors.Success
        complianceScore!! >= 50 -> SkieColors.Warning
        else -> SkieColors.Danger
    }

    val timestampText = complianceTimestamp?.let {
        val sdf = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
        "Last checked: ${sdf.format(Date(it))}"
    } ?: "Not yet checked"

    Box(modifier = rootModifier.background(SkieColors.White)) {
        Column(modifier = Modifier.fillMaxSize()) {
            SkieTopBar(
                title = "Home",
                trailing = {
                    IconButton(onClick = onBack) {
                        Text("✕", fontSize = 18.sp, color = SkieColors.Grey)
                    }
                }
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp)
            ) {
                // Welcome line
                Text(
                    "Welcome back",
                    fontSize = 24.sp, fontWeight = FontWeight.Bold, color = SkieColors.DeepNavy
                )
                Spacer(modifier = Modifier.height(20.dp))

                // Compliance score card — hero element of this screen
                SkieCard(backgroundColor = SkieColors.SkyTint) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Compliance Score", fontSize = 13.sp, color = SkieColors.Grey,
                            fontWeight = FontWeight.Medium)
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                text = complianceScore?.toString() ?: "—",
                                fontSize = 64.sp,
                                fontWeight = FontWeight.Bold,
                                color = scoreColor
                            )
                            if (complianceScore != null) {
                                Text("/100", fontSize = 16.sp, color = SkieColors.Grey,
                                    modifier = Modifier.padding(bottom = 10.dp, start = 2.dp))
                            }
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text(timestampText, fontSize = 12.sp, color = SkieColors.Grey)
                            Spacer(modifier = Modifier.width(8.dp))
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = "Refresh",
                                tint = SkieColors.SkyBlue,
                                modifier = Modifier
                                    .size(18.dp)
                                    .clickable {
                                        refreshing = true
                                        FirebaseDatabase.getInstance()
                                            .getReference("devices/$deviceId/complianceScore")
                                            .get()
                                            .addOnSuccessListener { snap ->
                                                complianceScore = snap.child("score")
                                                    .getValue(Long::class.java)?.toInt()
                                                complianceTimestamp = snap.child("timestamp")
                                                    .getValue(Long::class.java)
                                                refreshing = false
                                            }
                                            .addOnFailureListener { refreshing = false }
                                    }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(28.dp))

                // Setup & management steps
                SkieSectionHeader("Setup & Management")

                AdminStepCard(
                    title = "Connect Dashboard",
                    description = "Link this device to your Skie parent dashboard via QR code.",
                    accent = SkieColors.SkyBlue,
                    onClick = { onStepCard(1) }
                )
                AdminStepCard(
                    title = "Google Account",
                    description = "Verify a Google account is added before hardening.",
                    accent = SkieColors.Success,
                    onClick = { onStepCard(2) }
                )
                AdminStepCard(
                    title = "Device Compliance",
                    description = "Run the 3-layer security audit and view your compliance score.",
                    accent = SkieColors.Warning,
                    onClick = { onStepCard(3) }
                )
                AdminStepCard(
                    title = "App Management",
                    description = "Show or hide apps from the child's device.",
                    accent = SkieColors.Coral,
                    onClick = { onStepCard(4) }
                )
                AdminStepCard(
                    title = "Settings",
                    description = "Manage admin PIN, authenticator, and device options.",
                    accent = SkieColors.DeepNavy,
                    onClick = onSettings
                )

                Spacer(modifier = Modifier.height(28.dp))

                // Danger zone — Offboarding / Uninstall — demoted visually, quiet warning card
                SkieSectionHeader("Danger Zone")
                SkieCard(backgroundColor = SkieColors.DangerBg) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onOffboarding),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Offboarding / Uninstall", color = SkieColors.Danger,
                                fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            Text("Remove all supervision and prepare to uninstall Skiephone.",
                                color = SkieColors.Danger.copy(alpha = 0.75f), fontSize = 13.sp, lineHeight = 18.sp)
                        }
                        Text("›", color = SkieColors.Danger, fontSize = 20.sp)
                    }
                }

                Spacer(modifier = Modifier.height(28.dp))

                // About card
                SkieCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Skiephone", color = SkieColors.DeepNavy, fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp)
                            Text("v$versionName", color = SkieColors.Grey, fontSize = 13.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun AdminStepCard(
    title: String,
    description: String,
    accent: Color,
    onClick: () -> Unit
) {
    SkieCard(
        modifier = Modifier
            .padding(vertical = 5.dp)
            .clickable(onClick = onClick)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(4.dp, 40.dp)
                    .background(accent, RoundedCornerShape(2.dp))
            )
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = SkieColors.DeepNavy, fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp)
                Text(description, color = SkieColors.Grey, fontSize = 13.sp,
                    lineHeight = 18.sp)
            }
            Text("›", color = SkieColors.Grey, fontSize = 20.sp)
        }
    }
}