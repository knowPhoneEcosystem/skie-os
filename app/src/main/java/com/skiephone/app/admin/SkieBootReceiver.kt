package com.skiephone.app.admin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.skiephone.app.services.SkiePolicyService

class SkieBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                context.startService(Intent(context, SkiePolicyService::class.java))
            }
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                context.startService(Intent(context, SkiePolicyService::class.java))
            }
        }
    }
}