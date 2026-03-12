package com.skiephone.app.policy

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import com.skiephone.app.BuildConfig
import com.skiephone.app.admin.UpdateReceiver
import java.io.File

class SkieUpdateManager(private val context: Context) {

    companion object {
        const val TAG = "SkieUpdateManager"
    }

    fun startWatching() {
        FirebaseFirestore.getInstance()
            .collection("appConfig")
            .document("dpcVersion")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "Manifest listener error: ${error.message}")
                    return@addSnapshotListener
                }
                if (snapshot == null || !snapshot.exists()) return@addSnapshotListener

                val latestVersion = snapshot.getLong("versionCode")?.toInt()
                    ?: return@addSnapshotListener
                val apkUrl = snapshot.getString("apkUrl")
                    ?: return@addSnapshotListener
                val currentVersion = BuildConfig.VERSION_CODE

                Log.d(TAG, "Manifest received — latest: $latestVersion current: $currentVersion")

                if (latestVersion > currentVersion) {
                    Log.d(TAG, "Update available, downloading...")
                    downloadAndInstall(apkUrl)
                } else {
                    Log.d(TAG, "App is up to date")
                }
            }
    }

    private fun downloadAndInstall(apkUrl: String) {
        val storageRef = FirebaseStorage.getInstance().getReferenceFromUrl(apkUrl)
        val localFile = File(context.getExternalFilesDir(null), "skiephone_update.apk")

        storageRef.getFile(localFile)
            .addOnSuccessListener {
                Log.d(TAG, "APK downloaded, installing...")
                silentInstall(localFile)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "APK download failed: ${e.message}")
            }
    }

    private fun silentInstall(apkFile: File) {
        try {
            val packageInstaller = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
            )

            val sessionId = packageInstaller.createSession(params)
            val session = packageInstaller.openSession(sessionId)

            val inputStream = apkFile.inputStream()
            val outputStream = session.openWrite("skiephone_update", 0, apkFile.length())
            inputStream.copyTo(outputStream)
            session.fsync(outputStream)
            inputStream.close()
            outputStream.close()

            val intent = Intent(context, UpdateReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                sessionId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )

            session.commit(pendingIntent.intentSender)
            Log.d(TAG, "Silent install committed for session $sessionId")

        } catch (e: Exception) {
            Log.e(TAG, "Silent install failed: ${e.message}")
        }
    }
}