package dev.ott.sops.editor

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import com.intellij.openapi.vfs.VirtualFile
import dev.ott.sops.editor.settings.SopsSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

data class SopsResult(
    val stdout: String,
    val stderr: String,
    val exit_code: Int,
) {
    val success: Boolean get() = exit_code == 0
}

object SopsWrapper {

    /**
     * Runs `sops --version` against an explicit binary path, without touching application
     * settings. Used by the settings Verify button so the user can test a path they just
     * typed without committing it.
     */
    suspend fun version(exe_path: String): SopsResult = withContext(Dispatchers.IO) {
        execute(listOf("--version"), exe_path_override = exe_path)
    }

    suspend fun decrypt_file(file: VirtualFile): SopsResult = withContext(Dispatchers.IO) {
        execute(
            listOf("--decrypt", file.path),
            working_dir = file.parent?.path
        )
    }

    suspend fun decrypt_file_in_place(file: VirtualFile): SopsResult = withContext(Dispatchers.IO) {
        execute(
            listOf("--decrypt", "--in-place", file.path),
            working_dir = file.parent?.path
        )
    }

    suspend fun decrypt_text(text: String, format: SopsFormat): SopsResult = withContext(Dispatchers.IO) {
        val tmp_file = create_temp_file(text, format)
        try {
            val result = execute(
                listOf("--decrypt", tmp_file.toAbsolutePath().toString()),
                working_dir = tmp_file.parent.toAbsolutePath().toString()
            )
            result
        } finally {
            try {
                Files.deleteIfExists(tmp_file)
            } catch (e: Exception) {
                SopsLog.warn("Failed to delete temp file", e)
            }
        }
    }

    suspend fun encrypt_file(file: VirtualFile): SopsResult = withContext(Dispatchers.IO) {
        execute(
            listOf("--encrypt", file.path),
            working_dir = file.parent?.path
        )
    }

    suspend fun encrypt_file_in_place(file: VirtualFile): SopsResult = withContext(Dispatchers.IO) {
        execute(
            listOf("--encrypt", "--in-place", file.path),
            working_dir = file.parent?.path
        )
    }

    suspend fun encrypt_text(text: String, format: SopsFormat, source_dir: String? = null): SopsResult = withContext(Dispatchers.IO) {
        val parent_path = source_dir?.let { Path.of(it) }
        val tmp_file = if (parent_path != null && Files.isDirectory(parent_path)) {
            val tmp = Files.createTempFile(parent_path, ".sops-editor-tmp-", format.primary_extension)
            apply_owner_only_file_permissions(tmp)
            Files.writeString(tmp, text)
            tmp
        } else {
            create_temp_file(text, format)
        }
        try {
            val result = execute(
                listOf("--encrypt", tmp_file.toAbsolutePath().toString()),
                working_dir = tmp_file.parent.toAbsolutePath().toString()
            )
            result
        } finally {
            try {
                Files.deleteIfExists(tmp_file)
            } catch (e: Exception) {
                SopsLog.warn("Failed to delete temp file", e)
            }
        }
    }

    private fun create_temp_file(content: String, format: SopsFormat): Path {
        val extension = format.primary_extension
        val tmp_path = Files.createTempFile("sops-editor-", extension)
        apply_owner_only_file_permissions(tmp_path)
        Files.writeString(tmp_path, content)
        return tmp_path
    }

    /**
     * Restricts a temp file to owner read/write on POSIX file systems. Secrets may briefly
     * transit through these files during encrypt/decrypt round-trips, so they must never be
     * world-readable. No-op on Windows — tmpdir ACLs there already restrict by user by default.
     */
    private fun apply_owner_only_file_permissions(path: Path) {
        try {
            if (!path.fileSystem.supportedFileAttributeViews().contains("posix")) return
            Files.setPosixFilePermissions(
                path,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        } catch (_: Exception) {
        }
    }

    private fun execute(
        args: List<String>,
        working_dir: String? = null,
        exe_path_override: String? = null,
    ): SopsResult {
        val settings = SopsSettings.get_instance()

        val command = GeneralCommandLine().apply {
            exePath = exe_path_override ?: settings.sops_path
            addParameters(args)
            charset = Charsets.UTF_8
            environment.putAll(settings.get_environment())
            if (working_dir != null) {
                setWorkDirectory(working_dir)
            }
        }

        // Log only the sops subcommand (first argument is always a flag like --decrypt / --encrypt / --version).
        // Full argv, exe path and environment variables are intentionally omitted to avoid
        // leaking file paths and secret material (e.g. SOPS_AGE_KEY) into the on-disk log.
        val sops_subcommand = args.firstOrNull() ?: "(unknown)"
        SopsLog.info("Executing sops $sops_subcommand")

        return try {
            val output = ExecUtil.execAndGetOutput(command)
            val result = SopsResult(
                stdout = output.stdout,
                stderr = output.stderr,
                exit_code = output.exitCode,
            )
            if (result.success) {
                SopsLog.info("sops $sops_subcommand succeeded")
            } else {
                val sanitized_stderr = SopsLog.sanitize_for_log(result.stderr).take(500)
                SopsLog.warn("sops $sops_subcommand failed (exit ${result.exit_code}): $sanitized_stderr")
            }
            result
        } catch (e: Exception) {
            SopsLog.error("sops execution failed", e)
            SopsResult(
                stdout = "",
                stderr = e.message ?: "Unknown error",
                exit_code = -1,
            )
        }
    }
}
