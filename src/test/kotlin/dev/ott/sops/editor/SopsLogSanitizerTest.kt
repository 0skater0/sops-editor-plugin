package dev.ott.sops.editor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SopsLogSanitizerTest {

    @Test
    fun `redacts age secret key`() {
        val input = "Failed to decrypt with key AGE-SECRET-KEY-1QQPQYZ9CDS and fallback"
        val result = LogSanitizer.sanitize(input)
        assertFalse(result.contains("AGE-SECRET-KEY-1QQPQYZ9CDS"))
        assertTrue(result.contains("[REDACTED-SECRET]"))
    }

    @Test
    fun `redacts multiple age secret keys in same message`() {
        val input = "Keys: AGE-SECRET-KEY-AAAA and AGE-SECRET-KEY-BBBB"
        val result = LogSanitizer.sanitize(input)
        assertFalse(result.contains("AGE-SECRET-KEY-AAAA"))
        assertFalse(result.contains("AGE-SECRET-KEY-BBBB"))
    }

    @Test
    fun `redacts pem private key block`() {
        val input = """
            Error: -----BEGIN RSA PRIVATE KEY-----
            MIIEowIBAAKCAQEA3tP...
            -----END RSA PRIVATE KEY-----
            additional context
        """.trimIndent()
        val result = LogSanitizer.sanitize(input)
        assertFalse(result.contains("MIIEowIBAAKCAQEA"))
        assertTrue(result.contains("[REDACTED-SECRET]"))
        assertTrue(result.contains("additional context"))
    }

    @Test
    fun `truncates very long messages`() {
        val long_input = "x".repeat(5000)
        val result = LogSanitizer.sanitize(long_input)
        assertTrue(result.length < 5000)
        assertTrue(result.contains("[truncated"))
    }

    @Test
    fun `passes innocent messages through unchanged`() {
        val input = "Loaded file test.env with 42 bytes"
        assertEquals(input, LogSanitizer.sanitize(input))
    }

    @Test
    fun `redacts ssh public key authorization lines`() {
        val input = "SSH key detected: ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABAQC7..."
        val result = LogSanitizer.sanitize(input)
        assertFalse(result.contains("AAAAB3NzaC1yc2EAAAADAQABAAABAQC7"))
    }
}
