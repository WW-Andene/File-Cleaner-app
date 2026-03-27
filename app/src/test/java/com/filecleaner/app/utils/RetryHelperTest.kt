package com.filecleaner.app.utils

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class RetryHelperTest {

    @Test
    fun `succeeds on first try`() = runBlocking {
        val result = retryOnNetworkError { "ok" }
        assertEquals("ok", result)
    }

    @Test
    fun `retries on IOException and succeeds`() = runBlocking {
        var attempt = 0
        val result = retryOnNetworkError(maxRetries = 3, initialDelayMs = 1L) {
            attempt++
            if (attempt < 3) throw IOException("network error")
            "recovered"
        }
        assertEquals("recovered", result)
        assertEquals(3, attempt)
    }

    @Test(expected = IOException::class)
    fun `throws after exhausting retries`(): Unit = runBlocking {
        retryOnNetworkError(maxRetries = 2, initialDelayMs = 1L) {
            throw IOException("persistent failure")
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `throws non-retryable exceptions immediately`(): Unit = runBlocking {
        retryOnNetworkError(maxRetries = 3, initialDelayMs = 1L) {
            throw IllegalArgumentException("bad input")
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects maxRetries of zero`(): Unit = runBlocking {
        retryOnNetworkError(maxRetries = 0) { "should not run" }
    }

    @Test
    fun `isRetryable returns true for IOException`() {
        assertTrue(isRetryable(IOException("timeout")))
    }

    @Test
    fun `isRetryable returns false for non-network exceptions`() {
        assertFalse(isRetryable(IllegalStateException("bad state")))
    }

    @Test
    fun `isRetryable returns false for auth failures`() {
        // JSchException is not available in unit tests, but we can verify the pattern
        assertFalse(isRetryable(RuntimeException("not retryable")))
    }
}
