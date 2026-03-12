package com.skiephone.app.admin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.skiephone.app.launcher.LauncherActivity
import com.skiephone.app.services.SkiePolicyService

class SkieBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                context.startService(Intent(context, SkiePolicyService::class.java))
            }
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                // App was updated — restart service and bring launcher to front
                context.startService(Intent(context, SkiePolicyService::class.java))
                val launchIntent = Intent(context, LauncherActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
                context.startActivity(launchIntent)
            }
        }
    }
}