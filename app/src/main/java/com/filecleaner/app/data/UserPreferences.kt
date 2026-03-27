package com.filecleaner.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/**
 * Singleton wrapper for user preferences. Replaces all previously hardcoded thresholds
 * with configurable values backed by SharedPreferences.
 */
object UserPreferences {

    private const val PREFS_NAME = "raccoon_prefs"

    @Volatile
    private var appContext: Context? = null

    private val prefs: SharedPreferences by lazy {
        val ctx = appContext ?: throw IllegalStateException(
            "UserPreferences.init(context) must be called before accessing preferences")
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // F-027: Encrypted prefs for sensitive credentials (crash report GitHub token)
    private val securePrefs: SharedPreferences by lazy {
        val ctx = appContext ?: throw IllegalStateException(
            "UserPreferences.init(context) must be called before accessing preferences")
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        try {
            EncryptedSharedPreferences.create(
                "raccoon_secure_prefs",
                masterKeyAlias,
                ctx,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (_: Exception) {
            // Fallback to regular prefs if encryption fails (rare, e.g. corrupted keystore)
            ctx.getSharedPreferences("raccoon_secure_prefs_fallback", Context.MODE_PRIVATE)
        }
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        // Eagerly trigger the lazy init on the calling thread
        prefs
        securePrefs
    }

    // ── Scan thresholds ──

    var largeFileThresholdMb: Int
        get() = prefs.getInt("large_file_threshold_mb", 50)
        set(value) = prefs.edit().putInt("large_file_threshold_mb", value.coerceIn(1, 10_000)).apply()

    var staleDownloadDays: Int
        get() = prefs.getInt("stale_download_days", 90)
        set(value) = prefs.edit().putInt("stale_download_days", value.coerceIn(1, 3650)).apply()

    var maxLargeFiles: Int
        get() = prefs.getInt("max_large_files", 200)
        set(value) = prefs.edit().putInt("max_large_files", value.coerceIn(1, 10_000)).apply()

    // ── Display ──

    var defaultViewModeOrdinal: Int
        get() = prefs.getInt("default_view_mode", 0)
        set(value) = prefs.edit().putInt("default_view_mode", value.coerceIn(0, 2)).apply()

    var defaultSortOrder: Int
        get() = prefs.getInt("default_sort_order", 0)
        set(value) = prefs.edit().putInt("default_sort_order", value.coerceIn(0, 5)).apply()

    var showHiddenFiles: Boolean
        get() = prefs.getBoolean("show_hidden_files", false)
        set(value) = prefs.edit().putBoolean("show_hidden_files", value).apply()

    // ── Undo ──

    var undoTimeoutMs: Int
        get() = prefs.getInt("undo_timeout_ms", 8000)
        set(value) = prefs.edit().putInt("undo_timeout_ms", value.coerceIn(1000, 60_000)).apply()

    // ── Favorites & Protected (P3) ──

    var favoritePaths: Set<String>
        get() = prefs.getStringSet("favorite_paths", emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet("favorite_paths", value).apply()

    var protectedPaths: Set<String>
        get() = prefs.getStringSet("protected_paths", emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet("protected_paths", value).apply()

    @Synchronized
    fun toggleFavorite(path: String) {
        val current = favoritePaths.toMutableSet()
        if (path in current) current.remove(path) else current.add(path)
        favoritePaths = current
    }

    @Synchronized
    fun toggleProtected(path: String) {
        val current = protectedPaths.toMutableSet()
        if (path in current) current.remove(path) else current.add(path)
        protectedPaths = current
    }

    fun isFavorite(path: String): Boolean = path in favoritePaths
    fun isProtected(path: String): Boolean = path in protectedPaths

    // ── Appearance ──

    /** 0 = System default, 1 = Light, 2 = Dark */
    var themeMode: Int
        get() = prefs.getInt("theme_mode", 0)
        set(value) = prefs.edit().putInt("theme_mode", value.coerceIn(0, 2)).apply()

    /** F-068: High-contrast theme toggle for low-vision users */
    var highContrastEnabled: Boolean
        get() = prefs.getBoolean("high_contrast_enabled", false)
        set(value) = prefs.edit().putBoolean("high_contrast_enabled", value).apply()

    // ── Onboarding ──

    var hasSeenOnboarding: Boolean
        get() = prefs.getBoolean("has_seen_onboarding", false)
        set(value) = prefs.edit().putBoolean("has_seen_onboarding", value).apply()

    var hasSeenPrivacyNotice: Boolean
        get() = prefs.getBoolean("has_seen_privacy_notice", false)
        set(value) = prefs.edit().putBoolean("has_seen_privacy_notice", value).apply()

    // ── Crash Reporting ──

    var crashReportingEnabled: Boolean
        get() = prefs.getBoolean("crash_reporting_enabled", false)
        set(value) = prefs.edit().putBoolean("crash_reporting_enabled", value).apply()

    // F-027: Store GitHub token in EncryptedSharedPreferences (has repo scope)
    var crashReportGithubToken: String
        @Synchronized get() {
            // Migrate from plaintext prefs if present
            val plain = prefs.getString("crash_report_github_token", null)
            if (plain != null && plain.isNotEmpty()) {
                securePrefs.edit().putString("crash_report_github_token", plain).apply()
                prefs.edit().remove("crash_report_github_token").apply()
                return plain
            }
            return securePrefs.getString("crash_report_github_token", "") ?: ""
        }
        set(value) = securePrefs.edit().putString("crash_report_github_token", value).apply()

    /** GitHub repo in "owner/repo" format. */
    var crashReportRepo: String
        get() = prefs.getString("crash_report_repo", "WW-Andene/File-Cleaner-app") ?: "WW-Andene/File-Cleaner-app"
        set(value) = prefs.edit().putString("crash_report_repo", value).apply()

    // Auto dark mode schedule (-1 = disabled)
    var autoDarkStartHour: Int
        get() = prefs.getInt("auto_dark_start", -1)
        set(value) = prefs.edit().putInt("auto_dark_start", value).apply()

    var autoDarkEndHour: Int
        get() = prefs.getInt("auto_dark_end", -1)
        set(value) = prefs.edit().putInt("auto_dark_end", value).apply()
}
