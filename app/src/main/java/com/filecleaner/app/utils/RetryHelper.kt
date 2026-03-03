package com.filecleaner.app.utils

import com.jcraft.jsch.JSchException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException

/**
 * Retries a block up to [maxRetries] times with exponential backoff
 * on transient network errors. Non-retryable exceptions (e.g. auth
 * failures) are thrown immediately.
 */
@Suppress("MagicNumber")
inline fun <T> retryOnNetworkError(
    maxRetries: Int = 3,
    initialDelayMs: Long = 1000L,
    block: () -> T
): T {
    var lastException: Exception? = null
    repeat(maxRetries) { attempt ->
        try {
            return block()
        } catch (e: Exception) {
            if (!isRetryable(e)) throw e
            lastException = e
            if (attempt < maxRetries - 1) {
                val delay = initialDelayMs * (1L shl attempt) // 1s, 2s, 4s
                Thread.sleep(delay)
            }
        }
    }
    throw lastException!!
}

/**
 * Returns true if the exception represents a transient network error
 * that is worth retrying.
 */
fun isRetryable(e: Exception): Boolean {
    // JSch auth failures should not be retried
    if (e is JSchException && e.message?.contains("Auth fail", ignoreCase = true) == true) {
        return false
    }
    // Retry on known network/IO exceptions
    if (e is IOException || e is SocketTimeoutException || e is ConnectException) {
        return true
    }
    // JSch wraps network errors in JSchException
    if (e is JSchException) {
        val cause = e.cause
        if (cause is IOException || cause is SocketTimeoutException || cause is ConnectException) {
            return true
        }
        // Connection-related JSch errors (e.g. "timeout" in connect, "connection is closed")
        val msg = e.message?.lowercase() ?: ""
        if ("timeout" in msg || "connection" in msg || "socket" in msg) {
            return true
        }
    }
    return false
}
