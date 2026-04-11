package dev.ott.sops.editor.settings

import com.intellij.util.SystemProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SopsSettingsTest {

    @Test
    fun `resolve_path expands tilde`() {
        val home = SystemProperties.getUserHome()
        val result = SopsSettings.resolve_path("~/.config/sops/age/keys.txt")
        assertTrue(result.startsWith(home))
        assertTrue(result.endsWith(".config/sops/age/keys.txt"))
    }

    @Test
    fun `resolve_path expands HOME variable`() {
        val home = SystemProperties.getUserHome()
        val result = SopsSettings.resolve_path("\$HOME/secrets/keys.txt")
        assertTrue(result.startsWith(home))
    }

    @Test
    fun `resolve_path passes unchanged when no substitution applies`() {
        val input = "/absolute/path/to/file.env"
        assertEquals(input, SopsSettings.resolve_path(input))
    }

    @Test
    fun `resolve_path leaves unknown env var unchanged`() {
        val input = "\${UNLIKELY_TO_EXIST_XYZ_ABC_123}/keys.txt"
        val result = SopsSettings.resolve_path(input)
        // The unknown var reference should be preserved verbatim, not replaced with empty string.
        assertTrue(result.contains("\${UNLIKELY_TO_EXIST_XYZ_ABC_123}"))
    }

    @Test
    fun `resolve_path does not double-expand tilde mid-path`() {
        // `~` in the middle of a string is replaced unconditionally by the current
        // implementation, which matches the documented legacy behavior. This test pins
        // that behavior so any future change is deliberate.
        val home = SystemProperties.getUserHome()
        val result = SopsSettings.resolve_path("foo~bar")
        assertFalse(result.contains('~'))
        assertTrue(result.contains(home))
    }
}
