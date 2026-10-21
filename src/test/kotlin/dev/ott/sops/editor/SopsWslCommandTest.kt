package dev.ott.sops.editor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS

class SopsWslCommandTest {

    // Minimal stand-in for the platform parser so the mapping logic runs on every CI OS.
    private val fake_parser: (String) -> WslLocation? = { path ->
        val normalized = path.replace('\\', '/')
        val match = Regex("^//(?:wsl\\.localhost|wsl\\$)/([^/]+)(/.*)?$", RegexOption.IGNORE_CASE).find(normalized)
        match?.let { WslLocation(it.groupValues[1], it.groupValues[2].ifEmpty { "/" }) }
    }

    @Test
    fun `plan is null without a target path`() {
        assertNull(SopsWslCommand.plan(listOf("--version"), null, null, fake_parser))
    }

    @Test
    fun `plan is null for native paths`() {
        val args = listOf("--decrypt", "C:/work/secrets.env")
        assertNull(SopsWslCommand.plan(args, "C:/work/secrets.env", "C:/work", fake_parser))
        assertNull(SopsWslCommand.plan(listOf("--decrypt", "/home/u/a.yaml"), "/home/u/a.yaml", "/home/u", fake_parser))
    }

    @Test
    fun `plan rewrites only the target argument to its linux path`() {
        val target = "//wsl.localhost/Ubuntu/home/u/project/secrets.yaml"
        val args = listOf("--decrypt", "--input-type", "yaml", "--output-type", "yaml", target)
        val plan = SopsWslCommand.plan(args, target, "//wsl.localhost/Ubuntu/home/u/project", fake_parser)!!
        assertEquals("Ubuntu", plan.distribution_id)
        assertEquals(
            listOf("--decrypt", "--input-type", "yaml", "--output-type", "yaml", "/home/u/project/secrets.yaml"),
            plan.args,
        )
        assertEquals("/home/u/project", plan.linux_working_dir)
    }

    @Test
    fun `plan handles backslash temp paths from encrypt_text`() {
        val target = "\\\\wsl$\\Debian\\srv\\app\\.sops-editor-tmp-123.env"
        val plan = SopsWslCommand.plan(listOf("--encrypt", target), target, "\\\\wsl$\\Debian\\srv\\app", fake_parser)!!
        assertEquals("Debian", plan.distribution_id)
        assertEquals(listOf("--encrypt", "/srv/app/.sops-editor-tmp-123.env"), plan.args)
        assertEquals("/srv/app", plan.linux_working_dir)
    }

    @Test
    fun `plan drops a working dir from another distribution`() {
        val target = "//wsl.localhost/Ubuntu/home/u/a.yaml"
        val plan = SopsWslCommand.plan(listOf("--decrypt", target), target, "//wsl.localhost/Debian/home/u", fake_parser)!!
        assertNull(plan.linux_working_dir)
    }

    @Test
    fun `plan matches the working dir distribution case-insensitively`() {
        val target = "//wsl.localhost/Ubuntu/home/u/a.yaml"
        val plan = SopsWslCommand.plan(listOf("--decrypt", target), target, "//WSL.LOCALHOST/ubuntu/home/u", fake_parser)!!
        assertEquals("/home/u", plan.linux_working_dir)
    }

    @Test
    fun `plan keeps a missing working dir`() {
        val target = "//wsl.localhost/Ubuntu/a.yaml"
        val plan = SopsWslCommand.plan(listOf("--decrypt", target), target, null, fake_parser)!!
        assertNull(plan.linux_working_dir)
    }

    @Test
    fun `resolve_executable falls back to sops for blank input`() {
        assertEquals("sops", SopsWslCommand.resolve_executable(""))
        assertEquals("sops", SopsWslCommand.resolve_executable("   "))
        assertEquals("/usr/local/bin/sops", SopsWslCommand.resolve_executable(" /usr/local/bin/sops "))
    }

    @Test
    fun `output marker is random and shell safe`() {
        val first = SopsWslCommand.new_output_marker()
        val second = SopsWslCommand.new_output_marker()
        assertNotEquals(first, second)
        assertTrue(first.matches(Regex("^[A-Za-z0-9_]+$")))
        assertEquals("printf '%s' '$first'", SopsWslCommand.output_marker_command(first))
    }

    @Test
    fun `strip_output_marker drops profile output before the marker`() {
        val marker = SopsWslCommand.new_output_marker()
        val stdout = "Agent pid 123\nPROFILE-NOISE\n${marker}db_password: hunter2\n"
        assertEquals("db_password: hunter2\n", SopsWslCommand.strip_output_marker(stdout, marker))
    }

    @Test
    fun `strip_output_marker keeps output without profile noise intact`() {
        val marker = SopsWslCommand.new_output_marker()
        assertEquals("a: b\n", SopsWslCommand.strip_output_marker("${marker}a: b\n", marker))
        assertEquals("", SopsWslCommand.strip_output_marker(marker, marker))
    }

    @Test
    fun `strip_output_marker only cuts at the first occurrence`() {
        val marker = SopsWslCommand.new_output_marker()
        val payload = "\u0000ÿ binary $marker\r\nrest"
        assertEquals(payload, SopsWslCommand.strip_output_marker("noise${marker}$payload", marker))
    }

    @Test
    fun `strip_output_marker returns null when the marker is missing`() {
        val marker = SopsWslCommand.new_output_marker()
        assertNull(SopsWslCommand.strip_output_marker("Agent pid 123\nsecret: value\n", marker))
        assertNull(SopsWslCommand.strip_output_marker("", marker))
        assertNull(SopsWslCommand.strip_output_marker(marker.dropLast(1), marker))
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `platform parser maps both UNC prefixes and slash styles`() {
        assertEquals(
            WslLocation("Ubuntu", "/home/u/a.yaml"),
            SopsWslCommand.parse_unc_path("//wsl.localhost/Ubuntu/home/u/a.yaml"),
        )
        assertEquals(
            WslLocation("Ubuntu-24.04", "/home/u/a.yaml"),
            SopsWslCommand.parse_unc_path("\\\\wsl.localhost\\Ubuntu-24.04\\home\\u\\a.yaml"),
        )
        assertEquals(
            WslLocation("Debian", "/srv/app"),
            SopsWslCommand.parse_unc_path("\\\\wsl$\\Debian\\srv\\app"),
        )
        assertTrue(SopsWslCommand.is_wsl_path("//wsl$/Debian/srv/app"))
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `platform parser ignores drive letters and other UNC shares`() {
        assertNull(SopsWslCommand.parse_unc_path("C:/work/secrets.env"))
        assertNull(SopsWslCommand.parse_unc_path("C:\\work\\secrets.env"))
        assertNull(SopsWslCommand.parse_unc_path("\\\\fileserver\\share\\secrets.env"))
        assertFalse(SopsWslCommand.is_wsl_path("C:/work"))
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `platform parser never matches on non-Windows hosts`() {
        assertNull(SopsWslCommand.parse_unc_path("//wsl.localhost/Ubuntu/home/u/a.yaml"))
        assertFalse(SopsWslCommand.is_wsl_path("/home/u/a.yaml"))
    }
}
