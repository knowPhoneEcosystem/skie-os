package com.skiephone.app.policy

import android.content.Context
import com.google.firebase.database.DataSnapshot

object SkiePolicyCache {

    private const val PREFS = "skie_policy"

    fun save(context: Context, snapshot: DataSnapshot) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putBoolean("cameraDisabled", snapshot.child("cameraDisabled").getValue(Boolean::class.java) ?: false)
            putBoolean("schoolModeEnabled", snapshot.child("schoolModeEnabled").getValue(Boolean::class.java) ?: false)
            putBoolean("screenLockEnabled", snapshot.child("screenLockEnabled").getValue(Boolean::class.java) ?: false)
            putBoolean("kioskMode", snapshot.child("kioskMode").getValue(Boolean::class.java) ?: false)
            putString("focusMode", snapshot.child("focusMode").getValue(String::class.java) ?: "none")
            putString("focusApp", snapshot.child("focusApp").getValue(String::class.java))
            putString("focusIssuedBy", snapshot.child("focusIssuedBy").getValue(String::class.java))
            apply()
        }
    }

    fun clearAll(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    fun getFocusMode(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("focusMode", "none") ?: "none"

    fun isSchoolModeEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("schoolModeEnabled", false)
}