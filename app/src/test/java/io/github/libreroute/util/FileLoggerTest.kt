package io.github.libreroute.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileLoggerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun sanitizationMasksPasswordsAndSecrets() {
        val input = "Dialing MQTT with password=SuperSecret123 and token=Bearer_xyz987"
        val sanitized = FileLogger.sanitize(input)
        assertFalse(sanitized.contains("SuperSecret123"))
        assertFalse(sanitized.contains("Bearer_xyz987"))
        assertTrue(sanitized.contains("<redacted>"))
    }

    @Test
    fun sanitizationMasksPrivateKeysAndCookies() {
        val input = "Setting up session with private_key=abc123456 and cookie=session_id_val"
        val sanitized = FileLogger.sanitize(input)
        assertFalse(sanitized.contains("abc123456"))
        assertFalse(sanitized.contains("session_id_val"))
        assertTrue(sanitized.contains("<redacted>"))
    }
}
