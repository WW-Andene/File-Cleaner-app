package com.filecleaner.app.utils.dev

import android.content.Context
import android.content.SharedPreferences

/**
 * Simple feature flag system for safe rollouts.
 * Flags can be toggled at runtime via the debug panel
 * without requiring a new build.
 */
object FeatureFlags {

    private const val PREFS_NAME = "feature_flags"

    enum class Flag(val key: String, val defaultValue: Boolean, val description: String) {
        SIMILAR_PHOTOS("similar_photos", true, "Similar photo detection"),
        CLOUD_BACKUP("cloud_backup", true, "Scheduled cloud backups"),
        SMART_CLEAN("smart_clean", true, "Smart clean with preview"),
        FILE_SHREDDER("file_shredder", true, "Secure 3-pass file deletion"),
        SENSITIVE_SCANNER("sensitive_scanner", true, "Sensitive file detection"),
        APP_LOCK("app_lock", true, "Biometric app lock"),
        DIRECT_BROWSE("direct_browse", true, "Direct filesystem browsing"),
        CODE_EDITOR("code_editor", true, "Code editing with syntax highlight"),
        VIDEO_GIF("video_gif", true, "Video to GIF conversion"),
        STORAGE_FORECAST("storage_forecast", true, "Storage full prediction")
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(context: Context, flag: Flag): Boolean =
        prefs(context).getBoolean(flag.key, flag.defaultValue)

    fun setEnabled(context: Context, flag: Flag, enabled: Boolean) {
        prefs(context).edit().putBoolean(flag.key, enabled).apply()
    }

    fun toggle(context: Context, flag: Flag) {
        setEnabled(context, flag, !isEnabled(context, flag))
    }

    fun resetAll(context: Context) {
        prefs(context).edit().clear().apply()
    }

    /** Get all flags with their current state. */
    fun getAllFlags(context: Context): List<Pair<Flag, Boolean>> =
        Flag.entries.map { it to isEnabled(context, it) }
}
