package com.skiephone.app.launcher

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.skiephone.app.ui.theme.SkiePhoneTheme
import com.skiephone.app.policy.SkieConfig

class LauncherActivity : ComponentActivity() {

    private val whitelistedPackages = SkieConfig.whitelistedPackages

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SkiePhoneTheme {
                LauncherScreen(whitelistedPackages)
            }
        }
    }

    override fun onBackPressed() {
        // Block back press on launcher
    }
}

@Composable
fun LauncherScreen(whitelistedPackages: List<String>) {
    val context = LocalContext.current
    val pm = context.packageManager

    val availableApps = whitelistedPackages.mapNotNull { packageName ->
        try {
            val appInfo = pm.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
            Pair(pm.getApplicationLabel(appInfo).toString(), packageName)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "SkieOS",
                fontSize = 32.sp,
                modifier = Modifier.padding(top = 32.dp, bottom = 32.dp)
            )

            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(8.dp)
            ) {
                items(availableApps) { (appName, packageName) ->
                    AppIcon(appName = appName, packageName = packageName)
                }
            }
        }
    }
}

@Composable
fun AppIcon(appName: String, packageName: String) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .padding(12.dp)
            .clickable {
                val intent = context.packageManager.getLaunchIntentForPackage(packageName)
                intent?.let { context.startActivity(it) }
            },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(60.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(text = appName.first().toString(), fontSize = 24.sp)
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = appName, fontSize = 12.sp)
    }
}