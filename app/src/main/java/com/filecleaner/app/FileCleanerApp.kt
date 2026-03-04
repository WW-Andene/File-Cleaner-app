package com.filecleaner.app

import android.app.Application
import com.filecleaner.app.data.UserPreferences
import com.filecleaner.app.utils.CrashReporter

class FileCleanerApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // Initialize preferences early so CrashReporter can read the token
        UserPreferences.init(applicationContext)
        com.filecleaner.app.data.cloud.CloudConnectionStore.init(applicationContext)

        // Install crash handler (writes crash files to disk on uncaught exceptions)
        CrashReporter.install(applicationContext)

        // Upload any crash reports from previous sessions
        CrashReporter.uploadPendingCrashReports()
    }
}
