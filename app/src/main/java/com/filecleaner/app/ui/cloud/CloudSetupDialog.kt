package com.filecleaner.app.ui.cloud

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.filecleaner.app.R
import com.filecleaner.app.data.cloud.CloudConnection
import com.filecleaner.app.data.cloud.CloudConnectionStore
import com.google.android.material.chip.ChipGroup
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * Dialog for adding/configuring a new cloud connection.
 * Uses Material Design input fields and shows/hides fields based on provider type.
 */
object CloudSetupDialog {

    private const val PROVIDER_SFTP = 0
    private const val PROVIDER_WEBDAV = 1
    private const val PROVIDER_GDRIVE = 2

    fun show(context: Context, onAdded: (CloudConnection) -> Unit) {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_cloud_setup, null)

        val chipGroup = dialogView.findViewById<ChipGroup>(R.id.chip_group_provider)
        val tilHost = dialogView.findViewById<TextInputLayout>(R.id.til_host)
        val etHost = dialogView.findViewById<TextInputEditText>(R.id.et_host)
        val tilPort = dialogView.findViewById<TextInputLayout>(R.id.til_port)
        val etPort = dialogView.findViewById<TextInputEditText>(R.id.et_port)
        val tilUsername = dialogView.findViewById<TextInputLayout>(R.id.til_username)
        val etUsername = dialogView.findViewById<TextInputEditText>(R.id.et_username)
        val tilPassword = dialogView.findViewById<TextInputLayout>(R.id.til_password)
        val etPassword = dialogView.findViewById<TextInputEditText>(R.id.et_password)
        val etName = dialogView.findViewById<TextInputEditText>(R.id.et_name)
        val tvHelp = dialogView.findViewById<TextView>(R.id.tv_provider_help)

        var selectedProvider = PROVIDER_SFTP

        fun updateFieldVisibility(provider: Int) {
            selectedProvider = provider
            tilHost.error = null
            tilPort.error = null
            tilUsername.error = null
            when (provider) {
                PROVIDER_SFTP -> {
                    tilHost.visibility = View.VISIBLE
                    tilPort.visibility = View.VISIBLE
                    tilUsername.visibility = View.VISIBLE
                    tilPassword.visibility = View.VISIBLE
                    tilHost.hint = context.getString(R.string.cloud_sftp_host_hint)
                    tilPassword.hint = context.getString(R.string.cloud_password_hint)
                    etPort.setText("22")
                    tvHelp.text = context.getString(R.string.cloud_sftp_help)
                    tvHelp.visibility = View.VISIBLE
                }
                PROVIDER_WEBDAV -> {
                    tilHost.visibility = View.VISIBLE
                    tilPort.visibility = View.VISIBLE
                    tilUsername.visibility = View.VISIBLE
                    tilPassword.visibility = View.VISIBLE
                    tilHost.hint = context.getString(R.string.cloud_webdav_host_hint)
                    tilPassword.hint = context.getString(R.string.cloud_password_hint)
                    etPort.setText("443")
                    tvHelp.text = context.getString(R.string.cloud_webdav_help)
                    tvHelp.visibility = View.VISIBLE
                }
                PROVIDER_GDRIVE -> {
                    tilHost.visibility = View.GONE
                    tilPort.visibility = View.GONE
                    tilUsername.visibility = View.GONE
                    tilPassword.visibility = View.VISIBLE
                    tilPassword.hint = context.getString(R.string.cloud_gdrive_token_hint)
                    tvHelp.text = context.getString(R.string.cloud_gdrive_help)
                    tvHelp.visibility = View.VISIBLE
                }
            }
        }

        chipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isEmpty()) return@setOnCheckedStateChangeListener
            val provider = when (checkedIds[0]) {
                R.id.chip_sftp -> PROVIDER_SFTP
                R.id.chip_webdav -> PROVIDER_WEBDAV
                R.id.chip_gdrive -> PROVIDER_GDRIVE
                else -> PROVIDER_SFTP
            }
            updateFieldVisibility(provider)
        }

        updateFieldVisibility(PROVIDER_SFTP)

        val dialog = AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.cloud_add_connection))
            .setView(dialogView)
            .setPositiveButton(context.getString(R.string.cloud_connect), null)
            .setNegativeButton(context.getString(R.string.cancel), null)
            .show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val displayName = etName.text.toString().trim().ifEmpty { context.getString(R.string.cloud_default_server_name) }
            val host = etHost.text.toString().trim()
            val port = etPort.text.toString().toIntOrNull() ?: -1
            val username = etUsername.text.toString().trim()
            val password = etPassword.text.toString()

            // P4-B3-03: Validate inputs
            var valid = true
            if (selectedProvider != PROVIDER_GDRIVE && host.isBlank()) {
                tilHost.error = context.getString(R.string.cloud_error_host_required)
                valid = false
            }
            if (selectedProvider != PROVIDER_GDRIVE && port !in 1..65535) {
                tilPort.error = context.getString(R.string.cloud_error_port_range)
                valid = false
            }
            if (selectedProvider in listOf(PROVIDER_SFTP, PROVIDER_WEBDAV) && username.isBlank()) {
                tilUsername.error = context.getString(R.string.cloud_error_username_required)
                valid = false
            }
            if (!valid) return@setOnClickListener

            val connection = when (selectedProvider) {
                PROVIDER_SFTP -> CloudConnection.sftp(displayName, host, port, username).copy(authToken = password)
                PROVIDER_WEBDAV -> CloudConnection.webdav(displayName, host, username, password)
                PROVIDER_GDRIVE -> CloudConnection.googleDrive(displayName, password)
                else -> return@setOnClickListener
            }

            CloudConnectionStore.saveConnection(connection)
            onAdded(connection)
            dialog.dismiss()
        }
    }
}
