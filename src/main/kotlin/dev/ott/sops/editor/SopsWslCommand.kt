package dev.ott.sops.editor

import com.intellij.execution.wsl.WslPath
import java.util.UUID

/**
 * Decides whether a sops call has to run inside a WSL distribution and rewrites its path
 * arguments to Linux paths. The UNC parser is injected so the mapping is testable on any OS.
 */
internal data class WslLocation(
    val distribution_id: String,
    val linux_path: String,
)

internal data class WslSopsInvocation(
    val distribution_id: String,
    val args: List<String>,
    val linux_working_dir: String?,
)

internal object SopsWslCommand {

    const val DEFAULT_EXECUTABLE = "sops"

    // Exit code a POSIX shell returns when the command itself cannot be found.
    const val COMMAND_NOT_FOUND_EXIT_CODE = 127

    /**
     * Returns null when [target_path] is not on a WSL UNC path, so the caller keeps the native
     * call. Only the exact [target_path] argument is rewritten; flags and values stay untouched.
     */
    fun plan(
        args: List<String>,
        target_path: String?,
        working_dir: String?,
        parse_unc: (String) -> WslLocation? = ::parse_unc_path,
    ): WslSopsInvocation? {
        if (target_path == null) {
            return null
        }
        val target = parse_unc(target_path) ?: return null
        val linux_args = args.map { if (it == target_path) target.linux_path else it }
        val linux_working_dir = working_dir
            ?.let(parse_unc)
            ?.takeIf { it.distribution_id.equals(target.distribution_id, ignoreCase = true) }
            ?.linux_path
        return WslSopsInvocation(target.distribution_id, linux_args, linux_working_dir)
    }

    fun is_wsl_path(path: String, parse_unc: (String) -> WslLocation? = ::parse_unc_path): Boolean {
        return parse_unc(path) != null
    }

    fun resolve_executable(configured: String): String {
        return configured.trim().ifEmpty { DEFAULT_EXECUTABLE }
    }

    /**
     * Random per call, so neither profile output nor sops output can contain it by accident.
     * Only letters, digits and underscores, so it needs no shell escaping.
     */
    fun new_output_marker(): String {
        return "SOPS_EDITOR_OUTPUT_" + UUID.randomUUID().toString().replace("-", "") + "_"
    }

    // Runs inside the login shell after the profile scripts, right before sops.
    fun output_marker_command(marker: String): String {
        return "printf '%s' '$marker'"
    }

    /**
     * Drops everything the login shell printed before sops started (profile output such as
     * "Agent pid 123"). Only the first occurrence counts, sops output after it stays untouched
     * even if it happens to contain the marker. Returns null when the marker is missing, so the
     * caller never mistakes unknown output for sops output.
     */
    fun strip_output_marker(stdout: String, marker: String): String? {
        val index = stdout.indexOf(marker)
        if (index < 0) {
            return null
        }
        return stdout.substring(index + marker.length)
    }

    /**
     * Platform parser for `\\wsl.localhost\<distro>\...` and `\\wsl$\<distro>\...`, with either
     * slash style. Returns null on non-Windows hosts, so other platforms never take the WSL route.
     */
    fun parse_unc_path(path: String): WslLocation? {
        val wsl_path = try {
            WslPath.parseWindowsUncPath(path)
        } catch (_: Exception) {
            null
        } ?: return null
        return WslLocation(wsl_path.distributionId, wsl_path.linuxPath)
    }
}
