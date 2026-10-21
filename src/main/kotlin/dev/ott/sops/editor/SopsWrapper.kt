package dev.ott.sops.editor

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.process.ProcessOutput
import com.intellij.execution.wsl.WSLCommandLineOptions
import com.intellij.execution.wsl.WslPath
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.vfs.VirtualFile
import dev.ott.sops.editor.settings.SopsSettings
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.coroutines.cancellation.CancellationException

data class SopsResult(
    val stdout: String,
    val stderr: String,
    val exit_code: Int,
    // Set when stderr may carry local paths that the user should see but the log must not.
    val log_stderr: String? = null,
) {
    val success: Boolean get() = exit_code == 0

    val stderr_for_log: String get() = log_stderr ?: stderr
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
        val format = SopsFormat.from_file(file)
        execute(
            // Pass --input-type/--output-type explicitly so content-detected files with
            // non-standard extensions decrypt with the right store. Without these flags
            // sops falls back to the binary store on unknown extensions, which fails on
            // per-key encrypted YAML/JSON.
            listOf("--decrypt", "--input-type", format.sops_type, "--output-type", format.sops_type, file.path),
            working_dir = file.parent?.path,
            target_path = file.path,
        )
    }

    suspend fun decrypt_file_in_place(file: VirtualFile): SopsResult = withContext(Dispatchers.IO) {
        val format = SopsFormat.from_file(file)
        execute(
            listOf("--decrypt", "--input-type", format.sops_type, "--output-type", format.sops_type, "--in-place", file.path),
            working_dir = file.parent?.path,
            target_path = file.path,
        )
    }

    suspend fun encrypt_file_in_place(file: VirtualFile): SopsResult = withContext(Dispatchers.IO) {
        val format = SopsFormat.from_file(file)
        execute(
            listOf("--encrypt", "--input-type", format.sops_type, "--output-type", format.sops_type, "--in-place", file.path),
            working_dir = file.parent?.path,
            target_path = file.path,
        )
    }

    /**
     * Encrypts [text] for the file at [target_path], which does not have to exist yet. With sops
     * 3.10 or newer the plaintext only travels through stdin; older versions fall back to a
     * short-lived temporary file next to the target.
     */
    suspend fun encrypt_text(text: String, format: SopsFormat, target_path: String): SopsResult = withContext(Dispatchers.IO) {
        val parent_dir = parent_dir(target_path)
        val working_dir = parent_dir?.let { existing_dir(it) }
        run_preferring_stdin(
            target_path,
            working_dir,
            via_stdin = {
                execute(
                    SopsStdinArgs.encrypt(format, target_path),
                    working_dir = working_dir,
                    target_path = target_path,
                    stdin = text,
                )
            },
            via_temp_file = { encrypt_text_via_temp_file(text, format, parent_dir) },
        )
    }

    /**
     * Decrypts ciphertext that is not on disk, for example a VCS revision. [file_path] is the
     * path the content belongs to, if known; [context_path] is any path in the same project and
     * only decides whether sops runs in WSL. Plaintext never touches the disk: the fallback for
     * sops older than 3.10 writes the ciphertext, not the plaintext, to a temporary file.
     * [timeout_ms] bounds the whole sops call, see [DEFAULT_TIMEOUT_MS].
     */
    suspend fun decrypt_text(
        ciphertext: String,
        format: SopsFormat,
        file_path: String?,
        context_path: String?,
        timeout_ms: Int = DEFAULT_TIMEOUT_MS,
    ): SopsResult = withContext(Dispatchers.IO) {
        val route_path = file_path ?: context_path
        val working_dir = if (file_path != null) {
            parent_dir(file_path)?.let { existing_dir(it) }
        } else {
            context_path?.let { existing_dir(it) }
        }
        run_preferring_stdin(
            route_path,
            working_dir,
            via_stdin = {
                execute(
                    SopsStdinArgs.decrypt(format, file_path),
                    working_dir = working_dir,
                    target_path = route_path,
                    stdin = ciphertext,
                    timeout_ms = timeout_ms,
                )
            },
            via_temp_file = { decrypt_text_via_temp_file(ciphertext, format, route_path, working_dir, timeout_ms) },
        )
    }

    fun clear_version_cache() {
        stdin_support_cache.clear()
    }

    /**
     * Upper bound for a single sops call. Generous on purpose: sops may wait for a cloud KMS, a
     * hardware token or a pinentry passphrase prompt. It only stops calls that would otherwise
     * hang forever, for example a WSL shell or a sops waiting for input that never comes.
     */
    const val DEFAULT_TIMEOUT_MS = 120_000

    // Upper bound for handing the stdin payload over once sops has exited or was stopped.
    private const val STDIN_WRITER_TIMEOUT_MS = 10_000L

    private enum class StdinSupport {
        STDIN,
        TEMP_FILE,
        // Version unknown, but the encrypt and decrypt subcommands exist: try stdin first.
        TRY_STDIN,
    }

    private val stdin_support_cache = ConcurrentHashMap<String, StdinSupport>()

    private suspend fun run_preferring_stdin(
        route_path: String?,
        working_dir: String?,
        via_stdin: suspend () -> SopsResult,
        via_temp_file: suspend () -> SopsResult,
    ): SopsResult {
        val (cache_key, support) = stdin_support(route_path, working_dir)
        if (support == StdinSupport.TEMP_FILE) {
            return via_temp_file()
        }
        val result = via_stdin()
        if (support == StdinSupport.STDIN) {
            return result
        }
        if (SopsStdinArgs.rejects_stdin(result.exit_code, result.stderr)) {
            stdin_support_cache[cache_key] = StdinSupport.TEMP_FILE
            SopsLog.info("sops cannot read stdin, stdin mode off")
            return via_temp_file()
        }
        if (result.success) {
            stdin_support_cache[cache_key] = StdinSupport.STDIN
            SopsLog.info("sops read stdin, stdin mode on")
        }
        return result
    }

    /**
     * Decides once per binary and route how text reaches sops. The cache key contains the
     * configured path, so changing the setting starts a fresh detection. An unparsable version is
     * cached as well, so it is not probed again on every call; only a sops that does not start
     * at all is probed again.
     */
    private suspend fun stdin_support(route_path: String?, working_dir: String?): Pair<String, StdinSupport> {
        val settings = SopsSettings.get_instance()
        val wsl_distribution = route_path?.let { SopsWslCommand.parse_unc_path(it)?.distribution_id }
        val cache_key = if (wsl_distribution != null) {
            "wsl|$wsl_distribution|" + SopsWslCommand.resolve_executable(settings.wsl_sops_path)
        } else {
            "native|" + settings.sops_path
        }
        stdin_support_cache[cache_key]?.let { return cache_key to it }
        // Keeps the detection offline: plain --version asks GitHub for the latest release.
        val offline = mapOf("SOPS_DISABLE_VERSION_CHECK" to "true")
        val result = execute(listOf("--version"), working_dir = working_dir, target_path = route_path, extra_environment = offline)
        val parsed = if (result.success) SopsVersion.parse(result.stdout) else null
        if (parsed != null) {
            SopsLog.info("Detected sops $parsed, stdin mode " + if (parsed >= SopsVersion.STDIN_MINIMUM) "on" else "off")
            val support = if (parsed >= SopsVersion.STDIN_MINIMUM) StdinSupport.STDIN else StdinSupport.TEMP_FILE
            stdin_support_cache[cache_key] = support
            return cache_key to support
        }
        // sops before 3.9 has no encrypt subcommand and would take the word for a file name to
        // edit, so the stdin call is only tried when the subcommand is known.
        val help = execute(listOf("help", "encrypt"), working_dir = working_dir, target_path = route_path, extra_environment = offline)
        if (help.success) {
            SopsLog.warn("Could not detect the sops version, trying stdin first")
            stdin_support_cache[cache_key] = StdinSupport.TRY_STDIN
            return cache_key to StdinSupport.TRY_STDIN
        }
        if (result.success) {
            SopsLog.warn("Could not detect the sops version, using temporary files")
            stdin_support_cache[cache_key] = StdinSupport.TEMP_FILE
            return cache_key to StdinSupport.TEMP_FILE
        }
        // sops did not run at all, for example a wrong path. Nothing is cached, so fixing the
        // setting or installing sops takes effect, and no temporary file is written for a call
        // that cannot succeed.
        SopsLog.warn("Could not run sops to detect its version")
        return cache_key to StdinSupport.TRY_STDIN
    }

    // String based, so the parent keeps the slash style of the VFS path that the WSL mapping and
    // the existing callers use.
    private fun parent_dir(path: String): String? {
        val normalized = path.replace('\\', '/').trimEnd('/')
        return normalized.substringBeforeLast('/', "").ifEmpty { null }
    }

    private fun existing_dir(path: String): String? {
        return try {
            path.takeIf { Files.isDirectory(Path.of(it)) }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun decrypt_text_via_temp_file(
        ciphertext: String,
        format: SopsFormat,
        route_path: String?,
        working_dir: String?,
        timeout_ms: Int,
    ): SopsResult {
        val in_wsl = route_path != null && SopsWslCommand.is_wsl_path(route_path)
        val tmp_file = if (in_wsl) {
            working_dir?.let { create_temp_file_in_wsl_dir(Path.of(it), ciphertext, format) }
                ?: return SopsResult(stdout = "", stderr = SopsBundle.message("wsl.temp.file.failed"), exit_code = -1)
        } else {
            create_temp_file(ciphertext, format)
        }
        try {
            val tmp_path = tmp_file.toAbsolutePath().toString()
            return execute(
                listOf("--decrypt", "--input-type", format.sops_type, "--output-type", format.sops_type, tmp_path),
                working_dir = tmp_file.parent.toAbsolutePath().toString(),
                target_path = tmp_path,
                timeout_ms = timeout_ms,
            )
        } finally {
            delete_temp_file(tmp_file)
        }
    }

    private fun delete_temp_file(tmp_file: Path) {
        try {
            Files.deleteIfExists(tmp_file)
        } catch (e: Exception) {
            // Only the type: the exception message carries the temp file path.
            SopsLog.warn("Failed to delete temp file: ${e.javaClass.simpleName}")
        }
    }

    private suspend fun encrypt_text_via_temp_file(text: String, format: SopsFormat, source_dir: String?): SopsResult {
        val parent_path = source_dir?.let { Path.of(it) }
        val wsl_source_dir = source_dir?.takeIf { SopsWslCommand.is_wsl_path(it) }
        // The .sops.yaml creation_rules inside WSL only match next to the real file, so a WSL
        // target never falls back to the Windows temp dir.
        val tmp_file = if (wsl_source_dir != null) {
            create_temp_file_in_wsl_dir(Path.of(wsl_source_dir), text, format)
                ?: return SopsResult(
                    stdout = "",
                    stderr = SopsBundle.message("wsl.temp.file.failed"),
                    exit_code = -1,
                )
        } else if (parent_path != null && Files.isDirectory(parent_path)) {
            val tmp = Files.createTempFile(parent_path, ".sops-editor-tmp-", format.primary_extension)
            apply_owner_only_file_permissions(tmp)
            Files.writeString(tmp, text)
            tmp
        } else {
            create_temp_file(text, format)
        }
        try {
            val tmp_path = tmp_file.toAbsolutePath().toString()
            return execute(
                listOf("--encrypt", tmp_path),
                working_dir = tmp_file.parent.toAbsolutePath().toString(),
                target_path = tmp_path,
            )
        } finally {
            delete_temp_file(tmp_file)
        }
    }

    private fun create_temp_file_in_wsl_dir(dir: Path, content: String, format: SopsFormat): Path? {
        if (!Files.isDirectory(dir)) {
            SopsLog.warn("WSL source directory is not usable for the temp file")
            return null
        }
        var tmp: Path? = null
        return try {
            tmp = Files.createTempFile(dir, ".sops-editor-tmp-", format.primary_extension)
            Files.writeString(tmp, content)
            tmp
        } catch (e: Exception) {
            SopsLog.warn("Failed to create the temp file in the WSL source directory: ${e.javaClass.simpleName}")
            try {
                tmp?.let { Files.deleteIfExists(it) }
            } catch (_: Exception) {
            }
            null
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

    /**
     * Runs one sops call. Cancelling the calling coroutine, for example through the progress
     * indicator passed to run_blocking_cancellable, stops the process; so does [timeout_ms].
     */
    private suspend fun execute(
        args: List<String>,
        working_dir: String? = null,
        exe_path_override: String? = null,
        target_path: String? = null,
        stdin: String? = null,
        extra_environment: Map<String, String> = emptyMap(),
        timeout_ms: Int = DEFAULT_TIMEOUT_MS,
    ): SopsResult {
        val settings = SopsSettings.get_instance()
        val wsl_invocation = SopsWslCommand.plan(args, target_path, working_dir)

        // Log only the sops subcommand (first argument is always a flag like --decrypt or
        // --version, or a subcommand like encrypt).
        // Full argv, exe path and environment variables are intentionally omitted to avoid
        // leaking file paths and secret material (e.g. SOPS_AGE_KEY) into the on-disk log.
        val sops_subcommand = args.firstOrNull() ?: "(unknown)"
        SopsLog.info("Executing sops $sops_subcommand" + if (wsl_invocation != null) " (running in WSL)" else "")

        val output_marker = wsl_invocation?.let { SopsWslCommand.new_output_marker() }

        return try {
            val command = if (wsl_invocation != null && output_marker != null) {
                val wsl_target_path = requireNotNull(target_path) { "A WSL invocation always has a target path" }
                create_wsl_command(wsl_invocation, wsl_target_path, settings, output_marker, extra_environment)
            } else {
                GeneralCommandLine().apply {
                    exePath = exe_path_override ?: settings.sops_path
                    addParameters(args)
                    charset = Charsets.UTF_8
                    environment.putAll(settings.get_environment())
                    environment.putAll(extra_environment)
                    if (working_dir != null) {
                        setWorkDirectory(working_dir)
                    }
                }
            }
            val output = run_process(command, stdin, timeout_ms)
            val result = if (output.isTimeout) {
                SopsResult(
                    stdout = "",
                    stderr = SopsBundle.message("sops.timeout", timeout_ms / 1000),
                    exit_code = -1,
                )
            } else if (output_marker != null) {
                create_wsl_result(output, output_marker)
            } else {
                SopsResult(
                    stdout = output.stdout,
                    stderr = output.stderr,
                    exit_code = output.exitCode,
                )
            }
            if (result.success) {
                SopsLog.info("sops $sops_subcommand succeeded")
            } else {
                val sanitized_stderr = SopsLog.sanitize_for_log(result.stderr).take(500)
                SopsLog.warn("sops $sops_subcommand failed (exit ${result.exit_code}): $sanitized_stderr")
            }
            result
        } catch (e: CancellationException) {
            SopsLog.info("sops $sops_subcommand was canceled")
            throw e
        } catch (e: Exception) {
            // No throwable and no exception message: both carry the executable path, which would
            // end up in idea.log. The type and the OS error code are enough to tell a missing
            // binary from a permission problem.
            val failure = describe_launch_failure(e)
            SopsLog.error("sops execution failed: $failure")
            SopsResult(
                stdout = "",
                stderr = e.message ?: "Unknown error",
                exit_code = -1,
                log_stderr = "sops could not be started: $failure",
            )
        }
    }

    private val os_error_regex = Regex("error=(\\d+)")

    internal fun describe_launch_failure(e: Throwable): String {
        // Bounded, because a cause chain can be cyclic.
        val chain = generateSequence(e) { it.cause }.take(3).toList()
        val types = chain.joinToString(" <- ") { it.javaClass.simpleName }
        val os_error = chain.asSequence()
            .mapNotNull { os_error_regex.find(it.message.orEmpty())?.value }
            .firstOrNull()
        return if (os_error == null) types else "$types ($os_error)"
    }

    /**
     * Feeds [stdin] to the process while its output is already being drained, so a large
     * payload cannot block on a full pipe. Works for native processes and both WSL launch
     * routes, since the IJent process adapter and wsl.exe both forward stdin to the shell.
     * The process is destroyed on timeout and when the calling coroutine is cancelled.
     */
    private suspend fun run_process(command: GeneralCommandLine, stdin: String?, timeout_ms: Int): ProcessOutput {
        val process = command.createProcess()
        val handler = SopsProcessHandler(process)
        val writer = stdin?.let { payload ->
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    process.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                } catch (e: Exception) {
                    // sops exited before reading everything, for example a missing binary in WSL;
                    // its exit code and stderr already describe the failure.
                    SopsLog.debug("Writing to sops stdin stopped early: ${e.javaClass.simpleName}")
                }
            }
        }
        val indicator = EmptyProgressIndicator()
        val output = try {
            coroutineScope {
                // The process runner below blocks this thread and polls the indicator, so a
                // sibling coroutine turns the cancellation of this scope into an indicator cancel.
                // UNDISPATCHED enters the try block at once, so a cancellation that arrives
                // before the watcher would have been scheduled still cancels the indicator.
                val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        awaitCancellation()
                    } finally {
                        indicator.cancel()
                    }
                }
                try {
                    handler.runProcessWithProgressIndicator(indicator, timeout_ms, true)
                } finally {
                    watcher.cancel()
                }
            }
        } finally {
            await_writer(writer)
        }
        if (output.isCancelled) {
            throw CancellationException("sops call canceled")
        }
        return output
    }

    private fun await_writer(writer: Future<*>?) {
        if (writer == null) {
            return
        }
        try {
            writer.get(STDIN_WRITER_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            writer.cancel(true)
            SopsLog.warn("Writing to sops stdin did not finish in time")
        } catch (e: Exception) {
            SopsLog.debug("Waiting for the sops stdin writer failed: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Also stops the children of the process on destroy. sops can start helpers such as gpg, and
     * a wrapper script would otherwise leave the real sops running with the output pipes open.
     */
    private class SopsProcessHandler(process: Process) :
        // A neutral presentable name keeps paths from the command line out of handler logs.
        CapturingProcessHandler(process, Charsets.UTF_8, "sops") {

        override fun doDestroyProcess() {
            try {
                process.descendants().forEach { it.destroyForcibly() }
            } catch (_: UnsupportedOperationException) {
                // The IJent process adapter has no local process tree; destroying it ends the
                // remote process.
            }
            process.destroyForcibly()
        }
    }

    private fun create_wsl_result(output: ProcessOutput, output_marker: String): SopsResult {
        if (output.exitCode == SopsWslCommand.COMMAND_NOT_FOUND_EXIT_CODE) {
            return SopsResult(
                stdout = "",
                stderr = SopsBundle.message("wsl.sops.not.found"),
                exit_code = output.exitCode,
            )
        }
        // The callers write stdout into the file or show it as decrypted text, so without the
        // marker the output is never passed on, not even after a zero exit code.
        val sops_stdout = SopsWslCommand.strip_output_marker(output.stdout, output_marker)
            ?: return SopsResult(
                stdout = "",
                stderr = if (output.exitCode == 0) SopsBundle.message("wsl.output.marker.missing") else output.stderr,
                exit_code = if (output.exitCode == 0) -1 else output.exitCode,
            )
        return SopsResult(
            stdout = sops_stdout,
            stderr = output.stderr,
            exit_code = output.exitCode,
        )
    }

    private fun create_wsl_command(
        invocation: WslSopsInvocation,
        target_path: String,
        settings: SopsSettings,
        output_marker: String,
        extra_environment: Map<String, String>,
    ): GeneralCommandLine {
        val distribution = WslPath.parseWindowsUncPath(target_path)?.distribution
            ?: throw IllegalStateException(SopsBundle.message("wsl.distribution.not.found"))
        val command = GeneralCommandLine().apply {
            exePath = SopsWslCommand.resolve_executable(settings.wsl_sops_path)
            addParameters(invocation.args)
            charset = Charsets.UTF_8
            // The age key file setting holds a host path that sops inside WSL cannot open; there
            // sops reads its own default key file unless the custom environment overrides it.
            environment.putAll(settings.get_custom_environment())
            environment.putAll(extra_environment)
        }
        val options = WSLCommandLineOptions()
            .setExecuteCommandInShell(true)
            .setExecuteCommandInLoginShell(true)
            // Keeps the values off the command line on both launch routes. On the wsl.exe route
            // they travel via WSLENV instead of inlined export statements; IJent hands them over
            // through its own protocol as the process environment.
            .setPassEnvVarsUsingInterop(true)
            // Init commands run inside the login shell after the profile scripts, so the marker
            // separates profile output from sops output on both routes.
            .addInitCommand(SopsWslCommand.output_marker_command(output_marker))
        if (invocation.linux_working_dir != null) {
            options.setRemoteWorkingDirectory(invocation.linux_working_dir)
        }
        return distribution.patchCommandLine(command, null, options)
    }
}
