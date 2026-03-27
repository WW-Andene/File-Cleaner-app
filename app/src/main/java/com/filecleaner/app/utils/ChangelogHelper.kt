package com.filecleaner.app.utils

import android.content.Context
import com.filecleaner.app.BuildConfig
import com.filecleaner.app.R
import com.filecleaner.app.ui.common.RoundedDialogBuilder

/**
 * Shows a "What's New" dialog on first launch after an app update.
 * Tracks the last seen version code in SharedPreferences.
 */
object ChangelogHelper {

    private const val PREFS_NAME = "changelog"
    private const val KEY_LAST_VERSION = "last_seen_version"

    /** Call from MainActivity.onCreate() to show changelog if version changed. */
    fun showIfNeeded(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastVersion = prefs.getInt(KEY_LAST_VERSION, 0)
        val currentVersion = BuildConfig.VERSION_CODE

        if (lastVersion > 0 && lastVersion < currentVersion) {
            RoundedDialogBuilder(context)
                .setTitle(context.getString(R.string.changelog_title, BuildConfig.VERSION_NAME))
                .setMessage(context.getString(R.string.changelog_content))
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }

        prefs.edit().putInt(KEY_LAST_VERSION, currentVersion).apply()
    }
}
