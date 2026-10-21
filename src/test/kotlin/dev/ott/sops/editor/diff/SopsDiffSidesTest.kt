package dev.ott.sops.editor.diff

import dev.ott.sops.editor.SopsDetector
import dev.ott.sops.editor.SopsFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SopsDiffSidesTest {

    private val sops_yaml = """
        db_password: ENC[AES256_GCM,data:foo,tag:bar,type:str]
        sops:
            age:
                - recipient: age1example
                  enc: ENC[...]
            lastmodified: "2026-10-21T12:00:00Z"
            mac: ENC[AES256_GCM,data:abc,tag:def,type:str]
            version: 3.13.3
    """.trimIndent()

    private val sops_dotenv = """
        DB_PASSWORD=ENC[AES256_GCM,data:foo,tag:bar,type:str]
        sops_version=3.13.3
        sops_lastmodified=2026-10-21T12:00:00Z
        sops_mac=ENC[AES256_GCM,data:abc,tag:def,type:str]
    """.trimIndent()

    @Test
    fun `two encrypted revisions are both decrypted`() {
        val plan = SopsDiffSides.plan(listOf(sops_yaml, sops_yaml.replace("foo", "baz")))
        assertEquals(
            listOf(DiffSidePlan(DiffSideKind.DECRYPT, SopsFormat.YAML), DiffSidePlan(DiffSideKind.DECRYPT, SopsFormat.YAML)),
            plan,
        )
    }

    @Test
    fun `an added file has an empty left side`() {
        val plan = SopsDiffSides.plan(listOf("", sops_dotenv))
        assertEquals(
            listOf(DiffSidePlan(DiffSideKind.EMPTY), DiffSidePlan(DiffSideKind.DECRYPT, SopsFormat.DOTENV)),
            plan,
        )
    }

    @Test
    fun `a plaintext side next to an encrypted side is kept`() {
        val plan = SopsDiffSides.plan(listOf("db_password: placeholder\n", sops_yaml))
        assertEquals(
            listOf(DiffSidePlan(DiffSideKind.KEEP), DiffSidePlan(DiffSideKind.DECRYPT, SopsFormat.YAML)),
            plan,
        )
    }

    @Test
    fun `plain diffs are not offered`() {
        assertNull(SopsDiffSides.plan(listOf("a: 1\n", "a: 2\n")))
        assertNull(SopsDiffSides.plan(listOf("", "")))
        // A file that only mentions sops keywords is not an encrypted file.
        assertNull(SopsDiffSides.plan(listOf("sops: notes\n", "mac: none\n")))
    }

    @Test
    fun `non text sides and other side counts are not offered`() {
        assertNull(SopsDiffSides.plan(listOf(null, sops_yaml)))
        assertNull(SopsDiffSides.plan(listOf(sops_yaml)))
        assertNull(SopsDiffSides.plan(listOf(sops_yaml, sops_yaml, sops_yaml)))
    }

    @Test
    fun `oversized sides are not offered`() {
        val huge = sops_yaml + "\n#" + "x".repeat(SopsDiffSides.MAX_CONTENT_LENGTH)
        assertNull(SopsDiffSides.plan(listOf(huge, sops_yaml)))
    }

    @Test
    fun `head and tail windows still find the markers of a large side`() {
        // The sops block sits at the end of a large YAML file, past the head window.
        val large = "key: ENC[AES256_GCM,data:x,type:str]\n".repeat(5_000) + sops_yaml.substringAfter("\n")
        val window = SopsDetector.head_and_tail(large)
        assertTrue(window.length < large.length)
        assertEquals(
            listOf(DiffSidePlan(DiffSideKind.DECRYPT, SopsFormat.YAML), DiffSidePlan(DiffSideKind.KEEP)),
            SopsDiffSides.plan(listOf(window, SopsDetector.head_and_tail("a: 1\n"))),
        )
    }
}
