package dev.ott.sops.editor

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SopsDetectorTest {

    @Test
    fun `detects real sops yaml file`() {
        val yaml = """
            db:
                password: ENC[AES256_GCM,data:foo,tag:bar]
            sops:
                kms: []
                age:
                    - recipient: age1...
                      enc: ENC[...]
                lastmodified: '2026-04-11T12:00:00Z'
                mac: ENC[AES256_GCM,data:abc,tag:def]
                pgp: []
                encrypted_regex: ^(data|stringData)${'$'}
                version: 3.8.1
        """.trimIndent()

        assertTrue(SopsDetector.is_sops_content(yaml, SopsFormat.YAML))
    }

    @Test
    fun `detects real sops json file`() {
        val json = """
            {
                "db": { "password": "ENC[AES256_GCM,data:foo,tag:bar]" },
                "sops": {
                    "mac": "ENC[AES256_GCM,data:abc,tag:def]",
                    "lastmodified": "2026-04-11T12:00:00Z",
                    "version": "3.8.1"
                }
            }
        """.trimIndent()

        assertTrue(SopsDetector.is_sops_content(json, SopsFormat.JSON))
    }

    @Test
    fun `detects real sops dotenv file`() {
        val dotenv = """
            DB_PASSWORD=ENC[AES256_GCM,data:foo,tag:bar]
            sops_version=3.8.1
            sops_lastmodified=2026-04-11T12:00:00Z
            sops_mac=ENC[AES256_GCM,data:abc,tag:def]
        """.trimIndent()

        assertTrue(SopsDetector.is_sops_content(dotenv, SopsFormat.DOTENV))
    }

    @Test
    fun `detects real sops ini file`() {
        val ini = """
            [secrets]
            password = ENC[AES256_GCM,data:foo,tag:bar]
            [sops]
            mac = ENC[AES256_GCM,data:abc,tag:def]
            lastmodified = 2026-04-11T12:00:00Z
            version = 3.8.1
        """.trimIndent()

        assertTrue(SopsDetector.is_sops_content(ini, SopsFormat.INI))
    }

    @Test
    fun `detects real sops toml file`() {
        val toml = """
            [secrets]
            password = "ENC[AES256_GCM,data:foo,tag:bar]"
            [sops]
            mac = "ENC[AES256_GCM,data:abc,tag:def]"
            lastmodified = "2026-04-11T12:00:00Z"
            version = "3.8.1"
        """.trimIndent()

        assertTrue(SopsDetector.is_sops_content(toml, SopsFormat.TOML))
    }

    @Test
    fun `rejects innocent yaml with accidental keyword matches`() {
        // This is the exact false-positive case the earlier 3-keyword heuristic suffered from.
        val innocent = """
            app: my-app
            version: 1.0.0
            lastmodified: 2026-04-11
            description: this file is not sops-encrypted
        """.trimIndent()

        assertFalse(SopsDetector.is_sops_content(innocent, SopsFormat.YAML))
    }

    @Test
    fun `rejects yaml with sops word in a value`() {
        val innocent = """
            description: documentation mentions sops in passing
            mac: 00:11:22:33:44:55
        """.trimIndent()

        assertFalse(SopsDetector.is_sops_content(innocent, SopsFormat.YAML))
    }

    @Test
    fun `rejects json without sops key`() {
        val innocent = """
            { "name": "test", "mac": "00:11:22:33:44:55" }
        """.trimIndent()

        assertFalse(SopsDetector.is_sops_content(innocent, SopsFormat.JSON))
    }

    @Test
    fun `rejects dotenv with only partial sops markers`() {
        val incomplete = """
            API_KEY=secret
            sops_version=3.8.1
        """.trimIndent()

        assertFalse(SopsDetector.is_sops_content(incomplete, SopsFormat.DOTENV))
    }

    @Test
    fun `rejects empty content`() {
        assertFalse(SopsDetector.is_sops_content("", SopsFormat.YAML))
        assertFalse(SopsDetector.is_sops_content("", SopsFormat.JSON))
        assertFalse(SopsDetector.is_sops_content("", SopsFormat.DOTENV))
    }
}
