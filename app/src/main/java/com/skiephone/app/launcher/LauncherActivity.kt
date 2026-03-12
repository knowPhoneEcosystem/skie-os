package com.skiephone.app.launcher

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.runtime.collectAsState
import com.skiephone.app.policy.SkieConfig
import com.skiephone.app.policy.SkiePolicyCache
import com.skiephone.app.policy.SkiePolicyState
import com.skiephone.app.services.SkiePolicyService
import com.skiephone.app.ui.SkieStudentActivity
import com.skiephone.app.ui.theme.SkiePhoneTheme

class LauncherActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ensureServiceRunning()
        setContent {
            SkiePhoneTheme {
                LauncherScreen()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ensureServiceRunning()
    }

    @Suppress("MissingSuperCall")
    override fun onBackPressed() {
        // intentionally block back press
    }

    @Suppress("DEPRECATION")
    private fun ensureServiceRunning() {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val running = manager.getRunningServices(Integer.MAX_VALUE)
            .any { it.service.className == SkiePolicyService::class.java.name }
        if (!running) {
            startService(Intent(this, SkiePolicyService::class.java))
        }
    }
}

@Composable
fun LauncherScreen() {
    val context = LocalContext.current
    val pm = context.packageManager
    val refreshTrigger by SkiePolicyState.refreshTrigger.collectAsState()

    val cameraDisabled = remember(refreshTrigger) { SkiePolicyCache.getCameraDisabled(context) }
    val essentialAppsEnabled = remember(refreshTrigger) { SkiePolicyCache.getEssentialAppsEnabled(context) }
    val schoolAppsEnabled = remember(refreshTrigger) { SkiePolicyCache.getSchoolAppsEnabled(context) }

    val availableApps = remember(cameraDisabled, essentialAppsEnabled, schoolAppsEnabled) {
        val packages = mutableListOf<String>()
        packages.addAll(SkieConfig.dialerPackages)
        packages.addAll(SkieConfig.messagingPackages)
        if (essentialAppsEnabled) packages.addAll(SkieConfig.essentialApps)
        if (schoolAppsEnabled) packages.addAll(SkieConfig.schoolApps)
        if (!cameraDisabled) packages.addAll(SkieConfig.cameraApps)
        packages.mapNotNull { packageName ->
            try {
                val appInfo = pm.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
                Triple(
                    pm.getApplicationLabel(appInfo).toString(),
                    packageName,
                    pm.getApplicationIcon(packageName)
                )
            } catch (e: PackageManager.NameNotFoundException) {
                null
            }
        }
    }

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
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(64.dp))

            Text(
                text = "skie",
                style = MaterialTheme.typography.displaySmall,
                color = Color(0xFF1A1A2E)
            )

            Text(
                text = "your safe space",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF8A8AAA)
            )

            Spacer(modifier = Modifier.weight(1f))

            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                contentPadding = PaddingValues(bottom = 64.dp, top = 16.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    SkieStudentIcon()
                }
                items(availableApps) { (appName, packageName, icon) ->
                    AppIcon(appName = appName, packageName = packageName, icon = icon)
                }
            }
        }
    }
}

@Composable
fun SkieStudentIcon() {
    val context = LocalContext.current
    Column(
        modifier = Modifier.clickable {
            context.startActivity(Intent(context, SkieStudentActivity::class.java))
        },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xFF1A1A2E)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "✦",
                style = MaterialTheme.typography.headlineSmall,
                color = Color(0xFFFFFFFF)
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Skie Student",
            style = MaterialTheme.typography.labelMedium,
            color = Color(0xFF1A1A2E),
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun AppIcon(appName: String, packageName: String, icon: android.graphics.drawable.Drawable) {
    val context = LocalContext.current
    val bitmap = remember(packageName) {
        icon.toBitmap(width = 128, height = 128)
    }
    Column(
        modifier = Modifier.clickable {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            intent?.let { context.startActivity(it) }
        },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(18.dp))
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = appName,
                modifier = Modifier.fillMaxSize()
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = appName,
            style = MaterialTheme.typography.labelMedium,
            color = Color(0xFF1A1A2E),
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}