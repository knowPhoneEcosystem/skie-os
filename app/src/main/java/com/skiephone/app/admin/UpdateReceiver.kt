package com.skiephone.app.admin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import com.skiephone.app.services.SkiePolicyService

class UpdateReceiver : BroadcastReceiver() {
    companion object {
        const val TAG = "UpdateReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE
        )
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)

        when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                Log.d(TAG, "Update installed successfully — restarting service")
                context.startService(Intent(context, SkiePolicyService::class.java))
            }
            else -> {
                Log.e(TAG, "Update failed — status: $status, message: $message")
            }
        }
    }
}