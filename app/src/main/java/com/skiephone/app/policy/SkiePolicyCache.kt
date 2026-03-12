package com.skiephone.app.policy

import android.content.Context
import android.util.Log

object SkiePolicyCache {

    private const val PREFS_NAME = "skie_policy_cache"

    // Policy keys
    private const val KEY_CAMERA_DISABLED = "cameraDisabled"
    private const val KEY_SCHOOL_MODE_ENABLED = "schoolModeEnabled"
    private const val KEY_SCREEN_LOCK_ENABLED = "screenLockEnabled"

    // Category keys
    private const val KEY_ESSENTIAL_APPS_ENABLED = "essentialAppsEnabled"
    private const val KEY_SCHOOL_APPS_ENABLED = "schoolAppsEnabled"

    fun save(
        context: Context,
        cameraDisabled: Boolean,
        schoolModeEnabled: Boolean,
        screenLockEnabled: Boolean
    ) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_CAMERA_DISABLED, cameraDisabled)
            .putBoolean(KEY_SCHOOL_MODE_ENABLED, schoolModeEnabled)
            .putBoolean(KEY_SCREEN_LOCK_ENABLED, screenLockEnabled)
            .apply()
    }

    fun saveCategories(
        context: Context,
        essentialAppsEnabled: Boolean,
        schoolAppsEnabled: Boolean
    ) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ESSENTIAL_APPS_ENABLED, essentialAppsEnabled)
            .putBoolean(KEY_SCHOOL_APPS_ENABLED, schoolAppsEnabled)
            .apply()
    }

    fun getCameraDisabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_CAMERA_DISABLED, false)

    fun getSchoolModeEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SCHOOL_MODE_ENABLED, false)

    fun getScreenLockEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SCREEN_LOCK_ENABLED, false)

    fun getEssentialAppsEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ESSENTIAL_APPS_ENABLED, true)

    fun getSchoolAppsEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SCHOOL_APPS_ENABLED, true)

    fun clearAll(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().apply()
        Log.d("SkiePolicyCache", "Cache cleared")
    }

}