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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import java.text.SimpleDateFormat
import java.util.*

/**
 * Parent admin home screen.
 * Shows compliance score (RTDB, single source of truth), 4 step cards, about card.
 * Accessible only after PIN + TOTP auth.
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
        complianceScore == null -> Color(0xFF636366)
        complianceScore!! >= 80 -> Color(0xFF30D158)
        complianceScore!! >= 50 -> Color(0xFFFF9F0A)
        else -> Color(0xFFFF453A)
    }

    val timestampText = complianceTimestamp?.let {
        val sdf = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
        "Last checked: ${sdf.format(Date(it))}"
    } ?: "Not yet checked"

    Column(
        modifier = rootModifier
            .background(Color(0xFF0A0A0F))
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        // Top bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("SkieOS Admin", fontSize = 22.sp, fontWeight = FontWeight.Bold,
                color = Color.White)
            Row {
                IconButton(onClick = onSettings) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings",
                        tint = Color(0xFF8E8E93))
                }
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to child screen",
                        tint = Color(0xFF8E8E93))
                }
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

        // Compliance score card
        Surface(
            color = Color(0xFF1C1C1E),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Compliance Score", fontSize = 14.sp, color = Color(0xFF8E8E93))
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = complianceScore?.toString() ?: "—",
                        fontSize = 72.sp,
                        fontWeight = FontWeight.Bold,
                        color = scoreColor
                    )
                    if (complianceScore != null) {
                        Text("/100", fontSize = 18.sp, color = Color(0xFF636366),
                            modifier = Modifier.padding(bottom = 12.dp))
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(timestampText, fontSize = 12.sp, color = Color(0xFF636366))
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "Refresh",
                        tint = Color(0xFF0A84FF),
                        modifier = Modifier
                            .size(18.dp)
                            .clickable {
                                refreshing = true
                                // Re-fetch score from RTDB
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

        // Step cards
        Text("Setup & Management", fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
            color = Color(0xFF8E8E93))
        Spacer(modifier = Modifier.height(12.dp))

        AdminStepCard(
            title = "Connect Dashboard",
            description = "Link this device to your Skie parent dashboard via QR code.",
            accent = Color(0xFF0A84FF),
            onClick = { onStepCard(1) }
        )
        AdminStepCard(
            title = "Google Account",
            description = "Verify a Google account is added before hardening.",
            accent = Color(0xFF30D158),
            onClick = { onStepCard(2) }
        )
        AdminStepCard(
            title = "Device Compliance",
            description = "Run the 3-layer security audit and view your compliance score.",
            accent = Color(0xFFFF9F0A),
            onClick = { onStepCard(3) }
        )
        AdminStepCard(
            title = "App Management",
            description = "Show or hide apps from the child's device.",
            accent = Color(0xFFBF5AF2),
            onClick = { onStepCard(4) }
        )

        Spacer(modifier = Modifier.height(28.dp))

        // Danger zone — Offboarding / Uninstall
        Text("Danger Zone", fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
            color = Color(0xFFFF453A))
        Spacer(modifier = Modifier.height(12.dp))
        Surface(
            color = Color(0xFF2A1414),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOffboarding)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(4.dp, 40.dp)
                        .background(Color(0xFFFF453A), RoundedCornerShape(2.dp))
                )
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Offboarding / Uninstall", color = Color.White,
                        fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Text("Remove all supervision and prepare to uninstall SkieOS.",
                        color = Color(0xFFCC8888), fontSize = 13.sp, lineHeight = 18.sp)
                }
                Text("›", color = Color(0xFFCC8888), fontSize = 20.sp)
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

        // About card
        Surface(
            color = Color(0xFF1C1C1E),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("SkieOS", color = Color.White, fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp)
                    Text("v$versionName", color = Color(0xFF636366), fontSize = 13.sp)
                }
                Text("Device ID", color = Color(0xFF636366), fontSize = 11.sp,
                    textAlign = TextAlign.End)
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun AdminStepCard(
    title: String,
    description: String,
    accent: Color,
    onClick: () -> Unit
) {
    Surface(
        color = Color(0xFF1C1C1E),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(4.dp, 40.dp)
                    .background(accent, RoundedCornerShape(2.dp))
            )
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = Color.White, fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp)
                Text(description, color = Color(0xFF8E8E93), fontSize = 13.sp,
                    lineHeight = 18.sp)
            }
            Text("›", color = Color(0xFF636366), fontSize = 20.sp)
        }
    }
}