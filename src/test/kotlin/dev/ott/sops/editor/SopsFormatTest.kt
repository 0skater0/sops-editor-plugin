package dev.ott.sops.editor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SopsFormatTest {

    @Test
    fun `from_extension maps known extensions`() {
        assertEquals(SopsFormat.DOTENV, SopsFormat.from_extension(".env"))
        assertEquals(SopsFormat.DOTENV, SopsFormat.from_extension("env"))
        assertEquals(SopsFormat.YAML, SopsFormat.from_extension(".yaml"))
        assertEquals(SopsFormat.YAML, SopsFormat.from_extension(".yml"))
        assertEquals(SopsFormat.JSON, SopsFormat.from_extension(".json"))
        assertEquals(SopsFormat.INI, SopsFormat.from_extension(".ini"))
        assertEquals(SopsFormat.BINARY, SopsFormat.from_extension(".sops"))
        assertEquals(SopsFormat.BINARY, SopsFormat.from_extension(".bin"))
    }

    @Test
    fun `from_extension is case insensitive`() {
        assertEquals(SopsFormat.YAML, SopsFormat.from_extension(".YAML"))
        assertEquals(SopsFormat.JSON, SopsFormat.from_extension(".Json"))
        assertEquals(SopsFormat.INI, SopsFormat.from_extension(".INI"))
    }

    @Test
    fun `from_extension falls back to yaml for unknown`() {
        assertEquals(SopsFormat.YAML, SopsFormat.from_extension(".xyz"))
        assertEquals(SopsFormat.YAML, SopsFormat.from_extension(""))
    }

    @Test
    fun `primary_extension is the first entry`() {
        assertEquals(".env", SopsFormat.DOTENV.primary_extension)
        assertEquals(".yaml", SopsFormat.YAML.primary_extension)
        assertEquals(".json", SopsFormat.JSON.primary_extension)
        // Tempfile suffix triggers `.sops.yaml` rules like `path_regex: \.sops$`.
        assertEquals(".sops", SopsFormat.BINARY.primary_extension)
    }

    @Test
    fun `sops_type matches cli flag`() {
        assertEquals("dotenv", SopsFormat.DOTENV.sops_type)
        assertEquals("yaml", SopsFormat.YAML.sops_type)
        assertEquals("json", SopsFormat.JSON.sops_type)
        assertEquals("ini", SopsFormat.INI.sops_type)
        assertEquals("binary", SopsFormat.BINARY.sops_type)
    }
}
