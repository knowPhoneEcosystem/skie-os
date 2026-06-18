package com.skiephone.app.util

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object SkiePrefManager {

    private const val PREFS_NAME = "skie_secure_prefs"
    private const val KEY_WIZARD_DONE = "wizard_complete"
    private const val KEY_TOTP_SECRET = "totp_secret"

    private fun prefs(context: Context) = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        // Fallback to regular prefs if encryption fails (e.g. device without secure hardware)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isWizardComplete(context: Context): Boolean =
        prefs(context).getBoolean(KEY_WIZARD_DONE, false)

    fun setWizardComplete(context: Context) =
        prefs(context).edit().putBoolean(KEY_WIZARD_DONE, true).apply()

    fun saveTotpSecret(context: Context, secret: String) =
        prefs(context).edit().putString(KEY_TOTP_SECRET, secret).apply()

    fun getTotpSecret(context: Context): String? =
        prefs(context).getString(KEY_TOTP_SECRET, null)

    fun clearTotpSecret(context: Context) =
        prefs(context).edit().remove(KEY_TOTP_SECRET).apply()

    /**
     * Persist app toggle states from Step 4 app management.
     * Key: packageName, Value: true = enabled (visible), false = disabled (hidden/suspended)
     * Stored as JSON string: {"com.example.app":true,"com.other.app":false}
     */
    fun saveAppToggleStates(context: Context, states: Map<String, Boolean>) {
        val json = states.entries.joinToString(",", "{", "}") { (k, v) -> "\"$k\":$v" }
        prefs(context).edit().putString("app_toggle_states", json).apply()
    }

    fun getAppToggleStates(context: Context): Map<String, Boolean> {
        val json = prefs(context).getString("app_toggle_states", null) ?: return emptyMap()
        return try {
            json.trim('{', '}').split(",")
                .mapNotNull { entry ->
                    val parts = entry.split(":")
                    if (parts.size == 2) {
                        val key = parts[0].trim('"')
                        val value = parts[1].trim() == "true"
                        key to value
                    } else null
                }.toMap()
        } catch (e: Exception) { emptyMap() }
    }

    /**
     * Persist app display labels so they survive future hide/suspend cycles.
     * Without this, getApplicationLabel() can fail to resolve a name for an
     * already-hidden app on some OEMs, regressing the UI to show the raw
     * package name instead of the friendly app name.
     * Values are escaped (quotes/backslashes) since labels can contain
     * arbitrary unicode/punctuation unlike package names.
     */
    fun saveAppLabels(context: Context, labels: Map<String, String>) {
        val json = labels.entries.joinToString(",", "{", "}") { (k, v) ->
            "\"${escapeJson(k)}\":\"${escapeJson(v)}\""
        }
        prefs(context).edit().putString("app_labels", json).apply()
    }

    fun getAppLabels(context: Context): Map<String, String> {
        val json = prefs(context).getString("app_labels", null) ?: return emptyMap()
        return try {
            parseSimpleJsonStringMap(json)
        } catch (e: Exception) { emptyMap() }
    }

    private fun escapeJson(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun unescapeJson(s: String): String =
        s.replace("\\\"", "\"").replace("\\\\", "\\")

    /**
     * Minimal parser for a flat {"key":"value",...} string map with escaped
     * quotes/backslashes inside values. Avoids pulling in a JSON library for
     * this single use case.
     */
    private fun parseSimpleJsonStringMap(json: String): Map<String, String> {
        val body = json.trim().removePrefix("{").removeSuffix("}")
        val result = mutableMapOf<String, String>()
        var i = 0
        fun readQuotedString(): String {
            val sb = StringBuilder()
            i++ // skip opening quote
            while (i < body.length && body[i] != '"') {
                if (body[i] == '\\' && i + 1 < body.length) {
                    sb.append(body[i]); sb.append(body[i + 1]); i += 2
                } else {
                    sb.append(body[i]); i++
                }
            }
            i++ // skip closing quote
            return unescapeJson(sb.toString())
        }
        while (i < body.length) {
            while (i < body.length && (body[i] == ',' || body[i] == ' ')) i++
            if (i >= body.length || body[i] != '"') break
            val key = readQuotedString()
            while (i < body.length && (body[i] == ':' || body[i] == ' ')) i++
            if (i >= body.length || body[i] != '"') break
            val value = readQuotedString()
            result[key] = value
        }
        return result
    }
}