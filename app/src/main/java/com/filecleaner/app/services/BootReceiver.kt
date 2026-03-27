package com.filecleaner.app.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.filecleaner.app.widget.StorageWidget

/**
 * Receives BOOT_COMPLETED to re-schedule periodic workers and
 * refresh the widget after device restart.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        // Re-schedule storage check worker
        StorageCheckWorker.schedule(context)

        // Refresh widget
        StorageWidget.refreshAll(context)
    }
}
