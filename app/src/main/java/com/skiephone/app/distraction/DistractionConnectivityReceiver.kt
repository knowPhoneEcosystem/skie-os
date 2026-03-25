package com.skiephone.app.distraction

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log

/**
 * DistractionConnectivityReceiver — triggers RTDB sync when connectivity is restored.
 *
 * When the device was offline and a timer expired, the RTDB write was queued.
 * When connectivity is restored, Firebase delivers the queued write automatically.
 * This receiver additionally re-runs syncAndApply to ensure SharedPrefs + WorkManager
 * are consistent with RTDB after reconnection.
 *
 * Registered in AndroidManifest.xml for android.net.conn.CONNECTIVITY_CHANGE
 */
class DistractionConnectivityReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "DistractionConnectivity"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (!isConnected(context)) return
        Log.d(TAG, "Connectivity restored — syncing distraction timers")
        DistractionManager.syncAndApply(context)
    }

    private fun isConnected(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}