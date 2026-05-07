package dev.ott.sops.editor

import org.junit.jupiter.api.Assertions.assertEquals
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
        assertFalse(SopsDetector.is_sops_content("", SopsFormat.BINARY))
    }

    @Test
    fun `detects real sops binary envelope`() {
        val envelope = """
            {
                "data": "ENC[AES256_GCM,data:abc,iv:def,tag:ghi,type:str]",
                "sops": {
                    "age": [
                        {
                            "recipient": "age1...",
                            "enc": "-----BEGIN AGE ENCRYPTED FILE-----\nENC\n-----END AGE ENCRYPTED FILE-----\n"
                        }
                    ],
                    "lastmodified": "2026-05-02T14:18:33Z",
                    "mac": "ENC[AES256_GCM,data:mac,iv:iv,tag:tag,type:str]",
                    "version": "3.12.1"
                }
            }
        """.trimIndent()

        assertTrue(SopsDetector.is_sops_content(envelope, SopsFormat.BINARY))
    }

    @Test
    fun `rejects regular sops json document under binary format`() {
        // Structured per-key JSON has no top-level `data` ENC blob.
        val structured = """
            {
                "secret": "ENC[AES256_GCM,data:foo,tag:bar]",
                "sops": {
                    "mac": "ENC[AES256_GCM,data:abc,tag:def]",
                    "lastmodified": "2026-05-02T12:00:00Z",
                    "version": "3.8.1"
                }
            }
        """.trimIndent()

        assertFalse(SopsDetector.is_sops_content(structured, SopsFormat.BINARY))
    }

    @Test
    fun `rejects innocent json with data and sops keywords`() {
        val innocent = """
            {
                "data": "hello world",
                "sops": "is a great encryption tool"
            }
        """.trimIndent()

        assertFalse(SopsDetector.is_sops_content(innocent, SopsFormat.BINARY))
    }

    @Test
    fun `rejects binary envelope without metadata sibling`() {
        val incomplete = """
            {
                "data": "ENC[AES256_GCM,data:abc,tag:def]",
                "sops": {
                    "version": "3.12.1"
                }
            }
        """.trimIndent()

        assertFalse(SopsDetector.is_sops_content(incomplete, SopsFormat.BINARY))
    }

    @Test
    fun `detect_sops_format identifies yaml content`() {
        val yaml = """
            db:
                password: ENC[AES256_GCM,data:foo,tag:bar]
            sops:
                mac: ENC[AES256_GCM,data:abc,tag:def]
                lastmodified: '2026-04-11T12:00:00Z'
        """.trimIndent()
        assertEquals(SopsFormat.YAML, SopsDetector.detect_sops_format(yaml))
    }

    @Test
    fun `detect_sops_format identifies json content`() {
        val json = """
            {
                "db": { "password": "ENC[AES256_GCM,data:foo,tag:bar]" },
                "sops": {
                    "mac": "ENC[AES256_GCM,data:abc,tag:def]",
                    "lastmodified": "2026-04-11T12:00:00Z"
                }
            }
        """.trimIndent()
        assertEquals(SopsFormat.JSON, SopsDetector.detect_sops_format(json))
    }

    @Test
    fun `detect_sops_format identifies dotenv content`() {
        val dotenv = """
            DB_PASSWORD=ENC[AES256_GCM,data:foo,tag:bar]
            sops_version=3.8.1
            sops_lastmodified=2026-04-11T12:00:00Z
            sops_mac=ENC[AES256_GCM,data:abc,tag:def]
        """.trimIndent()
        assertEquals(SopsFormat.DOTENV, SopsDetector.detect_sops_format(dotenv))
    }

    @Test
    fun `detect_sops_format identifies ini content`() {
        val ini = """
            [secrets]
            password = ENC[AES256_GCM,data:foo,tag:bar]
            [sops]
            mac = ENC[AES256_GCM,data:abc,tag:def]
            lastmodified = 2026-04-11T12:00:00Z
        """.trimIndent()
        assertEquals(SopsFormat.INI, SopsDetector.detect_sops_format(ini))
    }

    @Test
    fun `detect_sops_format identifies binary envelope before json probe`() {
        // A binary envelope also satisfies the permissive JSON probe (it has `"sops"` and
        // `"mac"`), so the order in detect_sops_format matters: BINARY is tried first.
        val envelope = """
            {
                "data": "ENC[AES256_GCM,data:abc,iv:def,tag:ghi,type:str]",
                "sops": {
                    "lastmodified": "2026-05-02T14:18:33Z",
                    "mac": "ENC[AES256_GCM,data:mac,iv:iv,tag:tag,type:str]",
                    "version": "3.12.1"
                }
            }
        """.trimIndent()
        assertEquals(SopsFormat.BINARY, SopsDetector.detect_sops_format(envelope))
    }

    @Test
    fun `detect_sops_format returns null for plain text without sops markers`() {
        val plain = """
            key: value
            other: stuff
            note: this file has no encryption
        """.trimIndent()
        assertEquals(null, SopsDetector.detect_sops_format(plain))
    }

    @Test
    fun `detect_sops_format returns null for empty input`() {
        assertEquals(null, SopsDetector.detect_sops_format(""))
        assertEquals(null, SopsDetector.detect_sops_format("   \n\n  "))
    }
}
