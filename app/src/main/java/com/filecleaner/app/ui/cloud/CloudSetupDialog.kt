package com.filecleaner.app.ui.cloud

import android.content.Context
import android.text.InputType
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.filecleaner.app.R
import com.filecleaner.app.data.cloud.CloudConnection
import com.filecleaner.app.data.cloud.CloudConnectionStore
import com.filecleaner.app.data.cloud.ProviderType

/**
 * Dialog for adding/configuring a new cloud connection.
 */
object CloudSetupDialog {

    fun show(context: Context, onAdded: (CloudConnection) -> Unit) {
        val padding = context.resources.getDimensionPixelSize(R.dimen.spacing_lg)
        val fieldSpacing = context.resources.getDimensionPixelSize(R.dimen.spacing_sm)

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, 0)
        }

        // Provider type spinner
        val typeLabel = TextView(context).apply { text = context.getString(R.string.cloud_provider_type) }
        container.addView(typeLabel)

        val types = listOf("SFTP", "WebDAV", "Google Drive")
        val typeSpinner = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, types)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        container.addView(typeSpinner)

        // Display name
        val nameInput = EditText(context).apply {
            hint = context.getString(R.string.cloud_display_name)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        container.addView(nameInput)

        // Host / URL
        val hostInput = EditText(context).apply {
            hint = context.getString(R.string.cloud_host_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        container.addView(hostInput)

        // Port
        val portInput = EditText(context).apply {
            hint = context.getString(R.string.cloud_port_hint)
            inputType = InputType.TYPE_CLASS_NUMBER
            setText("22")
        }
        container.addView(portInput)

        // Username
        val userInput = EditText(context).apply {
            hint = context.getString(R.string.cloud_username_hint)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        container.addView(userInput)

        // Password / Token
        val passInput = EditText(context).apply {
            hint = context.getString(R.string.cloud_password_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        container.addView(passInput)

        val dialog = AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.cloud_add_connection))
            .setView(container)
            .setPositiveButton(context.getString(R.string.cloud_connect), null)
            .setNegativeButton(context.getString(R.string.cancel), null)
            .show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val displayName = nameInput.text.toString().trim().ifEmpty { "My Server" }
            val host = hostInput.text.toString().trim()
            val port = portInput.text.toString().toIntOrNull() ?: -1
            val username = userInput.text.toString().trim()
            val password = passInput.text.toString()
            val selectedType = typeSpinner.selectedItemPosition

            // P4-B3-03: Validate inputs
            var valid = true
            if (selectedType != 2 && host.isBlank()) {
                hostInput.error = "Host is required"
                valid = false
            }
            if (port !in 1..65535) {
                portInput.error = "Port must be 1-65535"
                valid = false
            }
            if (selectedType in 0..1 && username.isBlank()) {
                userInput.error = "Username is required"
                valid = false
            }
            if (!valid) return@setOnClickListener

            val connection = when (selectedType) {
                0 -> CloudConnection.sftp(displayName, host, port, username).copy(authToken = password)
                1 -> CloudConnection.webdav(displayName, host, username, password)
                2 -> CloudConnection.googleDrive(displayName, password)
                else -> return@setOnClickListener
            }

            CloudConnectionStore.saveConnection(connection)
            onAdded(connection)
            dialog.dismiss()
        }
    }
}
