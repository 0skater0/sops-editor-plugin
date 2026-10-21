package dev.ott.sops.editor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SopsStdinTest {

    @Test
    fun `parses the plain version line`() {
        assertEquals(SopsVersion(3, 12, 1), SopsVersion.parse("sops 3.12.1\n"))
    }

    @Test
    fun `parses the version when an update notice follows`() {
        val output = "sops 3.9.4 (latest)\n\n[warning] Note that in a future version, sops will no longer check ...\n"
        assertEquals(SopsVersion(3, 9, 4), SopsVersion.parse(output))
    }

    @Test
    fun `parses a v prefix and windows line endings`() {
        assertEquals(SopsVersion(3, 10, 0), SopsVersion.parse("sops v3.10.0\r\n"))
    }

    @Test
    fun `rejects output without a version line`() {
        assertNull(SopsVersion.parse(""))
        assertNull(SopsVersion.parse("bash: sops: command not found"))
        assertNull(SopsVersion.parse("[info] a new version of sops (v3.13.3) is available"))
    }

    @Test
    fun `stdin minimum is 3_10_0 and compares numerically`() {
        assertEquals(SopsVersion(3, 10, 0), SopsVersion.STDIN_MINIMUM)
        assertTrue(SopsVersion(3, 10, 0) >= SopsVersion.STDIN_MINIMUM)
        assertTrue(SopsVersion(3, 13, 3) >= SopsVersion.STDIN_MINIMUM)
        assertTrue(SopsVersion(4, 0, 0) >= SopsVersion.STDIN_MINIMUM)
        assertFalse(SopsVersion(3, 9, 4) >= SopsVersion.STDIN_MINIMUM)
        assertFalse(SopsVersion(3, 7, 3) >= SopsVersion.STDIN_MINIMUM)
        assertTrue(SopsVersion(3, 10, 0) > SopsVersion(3, 9, 99))
    }

    @Test
    fun `encrypt args read stdin and use the override path`() {
        val args = SopsStdinArgs.encrypt(SopsFormat.YAML, "/home/u/project/secrets/a.yaml")
        assertEquals(
            listOf(
                "encrypt",
                "--input-type", "yaml", "--output-type", "yaml",
                "--filename-override", "/home/u/project/secrets/a.yaml",
            ),
            args,
        )
    }

    @Test
    fun `decrypt args with and without an override path`() {
        assertEquals(
            listOf("decrypt", "--input-type", "dotenv", "--output-type", "dotenv", "--filename-override", "C:/p/.env"),
            SopsStdinArgs.decrypt(SopsFormat.DOTENV, "C:/p/.env"),
        )
        assertEquals(
            listOf("decrypt", "--input-type", "binary", "--output-type", "binary"),
            SopsStdinArgs.decrypt(SopsFormat.BINARY, null),
        )
    }

    @Test
    fun `stdin args never contain a positional file argument`() {
        // A trailing positional argument would make sops read that file instead of stdin.
        val encrypt = SopsStdinArgs.encrypt(SopsFormat.JSON, "/x/a.json")
        assertEquals("--filename-override", encrypt[encrypt.size - 2])
        val decrypt = SopsStdinArgs.decrypt(SopsFormat.INI, null)
        assertTrue(decrypt.last() == "ini")
    }

    @Test
    fun `a refused stdin call is told apart from a failed decryption`() {
        // sops 3.9: subcommand known, but no stdin support.
        assertTrue(SopsStdinArgs.rejects_stdin(100, "Error: no file specified\n"))
        // A build without --filename-override.
        assertTrue(SopsStdinArgs.rejects_stdin(1, "Incorrect Usage: flag provided but not defined: -filename-override\n"))
        assertFalse(SopsStdinArgs.rejects_stdin(0, ""))
        assertFalse(SopsStdinArgs.rejects_stdin(128, "Failed to get the data key required to decrypt the SOPS file."))
        assertFalse(SopsStdinArgs.rejects_stdin(127, "sops: command not found"))
        assertFalse(SopsStdinArgs.rejects_stdin(-1, "Cannot run program"))
    }

    @Test
    fun `wsl plan rewrites the override path to the linux path`() {
        val target = "//wsl.localhost/Ubuntu/home/u/project/secrets/a.yaml"
        val parser: (String) -> WslLocation? = { path ->
            val match = Regex("^//wsl\\.localhost/([^/]+)(/.*)$").find(path.replace('\\', '/'))
            match?.let { WslLocation(it.groupValues[1], it.groupValues[2]) }
        }
        val plan = requireNotNull(
            SopsWslCommand.plan(SopsStdinArgs.encrypt(SopsFormat.YAML, target), target, "//wsl.localhost/Ubuntu/home/u/project/secrets", parser),
        )
        assertEquals("/home/u/project/secrets/a.yaml", plan.args.last())
        assertEquals("/home/u/project/secrets", plan.linux_working_dir)
    }
}
