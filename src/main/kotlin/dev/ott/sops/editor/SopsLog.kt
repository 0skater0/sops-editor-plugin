package dev.ott.sops.editor

import com.intellij.openapi.diagnostic.Logger
import dev.ott.sops.editor.settings.SopsSettings
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Dual logger used throughout the plugin. Writes to the IntelliJ platform log (visible in
 * Help → Show Log) and to a rotating on-disk log file under the configured log directory.
 *
 * All messages pass through `sanitize_for_log` before being written so known secret patterns
 * (AGE secret keys, PEM-encoded private keys) never end up on disk or in shared logs.
 */
/**
 * Pure secret-masking logic. Extracted so it can be unit tested without booting the
 * IntelliJ platform. Add new patterns here, not to SopsLog.
 */
object LogSanitizer {
    private const val MAX_MESSAGE_LENGTH = 2000

    private val secret_patterns = listOf(
        Regex("AGE-SECRET-KEY-[A-Z0-9]+"),
        Regex("-----BEGIN [A-Z ]*PRIVATE KEY[A-Z ]*-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY[A-Z ]*-----"),
        Regex("ssh-(rsa|ed25519|ecdsa|dss)\\s+[A-Za-z0-9+/=]+"),
    )

    fun sanitize(message: String): String {
        var sanitized = message
        for (pattern in secret_patterns) {
            sanitized = pattern.replace(sanitized, "[REDACTED-SECRET]")
        }
        if (sanitized.length > MAX_MESSAGE_LENGTH) {
            sanitized = sanitized.take(MAX_MESSAGE_LENGTH) +
                "... [truncated ${sanitized.length - MAX_MESSAGE_LENGTH} chars]"
        }
        return sanitized
    }
}

object SopsLog {
    private val ide_log = Logger.getInstance("SopsEditor")
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    private const val MAX_LOG_SIZE_BYTES = 5L * 1024L * 1024L
    private const val MAX_ROTATED_FILES = 3

    private val default_log_dir: Path = Path.of(System.getProperty("java.io.tmpdir"), "sops-editor-plugin")

    val log_dir: Path
        get() {
            val configured = try {
                SopsSettings.get_instance().log_path
            } catch (_: Exception) {
                ""
            }
            return if (configured.isNotBlank()) {
                Path.of(SopsSettings.resolve_path(configured))
            } else {
                default_log_dir
            }
        }

    val log_file: Path
        get() = log_dir.resolve("sops-editor.log")

    /**
     * Masks known secret patterns and caps message length. Thin delegate to [LogSanitizer];
     * kept here so existing call sites don't need to import the sanitizer object.
     */
    fun sanitize_for_log(message: String): String = LogSanitizer.sanitize(message)

    fun info(message: String) {
        val clean = sanitize_for_log(message)
        ide_log.info(clean)
        write_to_file("INFO", clean)
    }

    fun warn(message: String, throwable: Throwable? = null) {
        val clean = sanitize_for_log(message)
        ide_log.warn(clean, throwable)
        write_to_file("WARN", clean + format_throwable_suffix(throwable))
    }

    fun error(message: String, throwable: Throwable? = null) {
        val clean = sanitize_for_log(message)
        ide_log.warn(clean, throwable)
        write_to_file("ERROR", clean + format_throwable_suffix(throwable))
    }

    fun debug(message: String) {
        val clean = sanitize_for_log(message)
        ide_log.debug(clean)
        write_to_file("DEBUG", clean)
    }

    private fun format_throwable_suffix(throwable: Throwable?): String {
        if (throwable == null) return ""
        val name = throwable.javaClass.simpleName
        val msg = sanitize_for_log(throwable.message ?: "")
        return " | $name: $msg"
    }

    private fun write_to_file(level: String, message: String) {
        try {
            val dir = log_dir
            val dir_created = !Files.exists(dir)
            if (dir_created) {
                Files.createDirectories(dir)
                apply_owner_only_permissions(dir, is_directory = true)
            }
            rotate_if_needed()
            val file = log_file
            val file_existed = Files.exists(file)
            val timestamp = LocalDateTime.now().format(formatter)
            val line = "[$timestamp] [$level] $message\n"
            Files.writeString(file, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
            if (!file_existed) {
                apply_owner_only_permissions(file, is_directory = false)
            }
        } catch (_: Exception) {
            // Intentionally swallow: logging failures must never crash the plugin.
        }
    }

    private fun rotate_if_needed() {
        try {
            val file = log_file
            if (!Files.exists(file) || Files.size(file) < MAX_LOG_SIZE_BYTES) return
            for (i in MAX_ROTATED_FILES downTo 1) {
                val src = if (i == 1) file else log_dir.resolve("sops-editor.log.${i - 1}")
                val dst = log_dir.resolve("sops-editor.log.$i")
                if (Files.exists(src)) {
                    Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING)
                }
            }
            Files.writeString(file, "", StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
            apply_owner_only_permissions(file, is_directory = false)
        } catch (_: Exception) {
        }
    }

    private fun apply_owner_only_permissions(path: Path, is_directory: Boolean) {
        try {
            if (!path.fileSystem.supportedFileAttributeViews().contains("posix")) return
            val perms = if (is_directory) {
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                )
            } else {
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                )
            }
            Files.setPosixFilePermissions(path, perms)
        } catch (_: Exception) {
        }
    }

    fun clear() {
        try {
            Files.deleteIfExists(log_file)
            for (i in 1..MAX_ROTATED_FILES) {
                Files.deleteIfExists(log_dir.resolve("sops-editor.log.$i"))
            }
        } catch (_: Exception) {
        }
    }

    fun read(): String {
        return try {
            if (Files.exists(log_file)) Files.readString(log_file) else ""
        } catch (_: Exception) {
            ""
        }
    }
}
