package com.filecleaner.app.utils.security

import android.content.Context
import android.content.SharedPreferences
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * App lock manager — requires biometric or PIN authentication
 * before granting access to the app.
 *
 * When enabled, shows a biometric prompt on app launch and
 * after returning from background (configurable timeout).
 */
object AppLockManager {

    private const val PREFS_NAME = "app_lock"
    private const val KEY_ENABLED = "lock_enabled"
    private const val KEY_LAST_UNLOCK = "last_unlock_ms"
    private const val KEY_TIMEOUT_MS = "timeout_ms"
    private const val DEFAULT_TIMEOUT_MS = 60_000L // 1 minute

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var isEnabled: Boolean
        get() = false // Read from prefs at call site
        set(_) {}

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun getTimeoutMs(context: Context): Long =
        prefs(context).getLong(KEY_TIMEOUT_MS, DEFAULT_TIMEOUT_MS)

    fun setTimeoutMs(context: Context, ms: Long) {
        prefs(context).edit().putLong(KEY_TIMEOUT_MS, ms).apply()
    }

    /** Returns true if the app needs re-authentication. */
    fun needsUnlock(context: Context): Boolean {
        if (!isEnabled(context)) return false
        val lastUnlock = prefs(context).getLong(KEY_LAST_UNLOCK, 0)
        val timeout = getTimeoutMs(context)
        return System.currentTimeMillis() - lastUnlock > timeout
    }

    /** Record successful authentication. */
    fun recordUnlock(context: Context) {
        prefs(context).edit().putLong(KEY_LAST_UNLOCK, System.currentTimeMillis()).apply()
    }

    /** Check if biometric authentication is available on this device. */
    fun canUseBiometric(context: Context): Boolean {
        val biometricManager = BiometricManager.from(context)
        return biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        ) == BiometricManager.BIOMETRIC_SUCCESS
    }

    /** Show the biometric/PIN prompt. */
    fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        onSuccess: () -> Unit,
        onFailure: () -> Unit
    ) {
        val executor = ContextCompat.getMainExecutor(activity)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                recordUnlock(activity)
                onSuccess()
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                    errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                    onFailure()
                }
            }
            override fun onAuthenticationFailed() {
                // Don't call onFailure here — user can retry
            }
        }

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()

        BiometricPrompt(activity, executor, callback).authenticate(promptInfo)
    }
}
