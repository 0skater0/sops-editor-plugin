package dev.ott.sops.editor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SopsNewFileActionTest {

    @Test
    fun `valid filenames pass`() {
        assertEquals(FilenameValidation.OK, validate_new_filename("secrets.env"))
        assertEquals(FilenameValidation.OK, validate_new_filename(".env"))
        assertEquals(FilenameValidation.OK, validate_new_filename("prod.yaml"))
        assertEquals(FilenameValidation.OK, validate_new_filename("my-config_v2.json"))
        assertEquals(FilenameValidation.OK, validate_new_filename("a"))
    }

    @Test
    fun `empty and blank names are rejected`() {
        assertEquals(FilenameValidation.EMPTY, validate_new_filename(""))
        assertEquals(FilenameValidation.EMPTY, validate_new_filename("   "))
        assertEquals(FilenameValidation.EMPTY, validate_new_filename("\t\n"))
    }

    @Test
    fun `path traversal attempts are rejected`() {
        assertEquals(FilenameValidation.INVALID_CHARACTERS, validate_new_filename(".."))
        assertEquals(FilenameValidation.INVALID_CHARACTERS, validate_new_filename("../evil.env"))
        assertEquals(FilenameValidation.INVALID_CHARACTERS, validate_new_filename("..\\evil.env"))
        assertEquals(FilenameValidation.INVALID_CHARACTERS, validate_new_filename("foo/../bar.env"))
        assertEquals(FilenameValidation.INVALID_CHARACTERS, validate_new_filename("foo/bar.env"))
        assertEquals(FilenameValidation.INVALID_CHARACTERS, validate_new_filename("foo\\bar.env"))
        assertEquals(FilenameValidation.INVALID_CHARACTERS, validate_new_filename("/etc/passwd"))
        assertEquals(FilenameValidation.INVALID_CHARACTERS, validate_new_filename("C:\\Windows\\system32\\evil.env"))
    }

    @Test
    fun `unicode and control characters are rejected`() {
        assertEquals(FilenameValidation.INVALID_CHARACTERS, validate_new_filename("evil\u0000.env"))
        assertEquals(FilenameValidation.INVALID_CHARACTERS, validate_new_filename("file with spaces.env"))
        assertEquals(FilenameValidation.INVALID_CHARACTERS, validate_new_filename("fílè.env"))
        assertEquals(FilenameValidation.INVALID_CHARACTERS, validate_new_filename("name?.env"))
        assertEquals(FilenameValidation.INVALID_CHARACTERS, validate_new_filename("name*.env"))
    }

    @Test
    fun `names exceeding 255 characters are rejected`() {
        val long = "a".repeat(256) + ".env"
        assertEquals(FilenameValidation.TOO_LONG, validate_new_filename(long))
    }

    @Test
    fun `name at exactly 255 characters is allowed`() {
        val at_limit = "a".repeat(251) + ".env"
        assertEquals(FilenameValidation.OK, validate_new_filename(at_limit))
    }
}
