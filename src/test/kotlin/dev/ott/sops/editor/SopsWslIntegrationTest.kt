package dev.ott.sops.editor

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.util.ProgressIndicatorBase
import com.intellij.openapi.util.Computable
import com.intellij.platform.ijent.IjentExecFileProvider
import com.intellij.platform.ijent.IjentPlatform
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.registry.Registry
import com.intellij.testFramework.replaceService
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.TestApplicationManager
import com.intellij.testFramework.common.ThreadLeakTracker
import dev.ott.sops.editor.settings.SopsSettings
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit

/**
 * End-to-end check of the WSL route against a real distribution. Opt-in via SOPS_WSL_E2E=1 because
 * it needs a prepared distro: sops and age on the login-shell PATH, a default age key and a test
 * project with a `.sops.yaml` (rule `secrets/.*\.(yaml|env)$`) plus an encrypted dummy
 * `secrets/a.yaml` in `~/sops-e2e`, next to a plaintext `outside.yaml` in the project root that
 * no creation rule matches (case 4 expects encrypting it to fail). SOPS_WSL_E2E_DISTRO and SOPS_WSL_E2E_HOME override distro
 * (default Ubuntu-24.04) and home dir (default `$HOME` of the distro's default user),
 * SOPS_WSL_E2E_MODE picks the launch route (`ijent`, the IDE default, or `wslexe`). Run with
 * `--rerun`, Gradle does not track these variables as task inputs. Some cases temporarily append
 * lines to `~/.profile` (no `.bash_profile` or `.bash_login` expected) and restore it from a
 * backup, checked by checksum. Cases 13 and 14 place short-lived wrapper scripts in
 * `~/.sops-e2e-backup`: one reports an old sops version to cover the temporary-file fallback,
 * one sleeps to check that cancel and timeout stop the process.
 */
@EnabledOnOs(OS.WINDOWS)
@EnabledIfEnvironmentVariable(named = "SOPS_WSL_E2E", matches = "1")
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SopsWslIntegrationTest {

    companion object {
        private val distro = System.getenv("SOPS_WSL_E2E_DISTRO") ?: "Ubuntu-24.04"
        // Lazy so that loading the class on a skipped run never starts wsl.exe.
        private val linux_home by lazy { System.getenv("SOPS_WSL_E2E_HOME") ?: query_wsl_home() }
        private val linux_project by lazy { "$linux_home/sops-e2e" }
        private val unc_project by lazy { "//wsl.localhost/$distro$linux_project" }
        private const val DUMMY_PASSWORD = "hunter2"
        private const val DUMMY_TOKEN = "test-token-123"

        private lateinit var saved_state: SopsSettings.State
        private lateinit var log_dir: Path

        // Since 2024.2 the IDE launches WSL processes through the IJent agent by default; the
        // test application swaps in a stub exec provider, so "ijent" restores the production one
        // and "wslexe" disables the agent to cover the classic wsl.exe route.
        private val mode = System.getenv("SOPS_WSL_E2E_MODE") ?: "ijent"
        private const val IJENT_REGISTRY_KEY = "wsl.use.remote.agent.for.launch.processes"
        private val disposable = Disposer.newDisposable()
        private var saved_registry: Boolean = true

        @JvmStatic
        @BeforeAll
        fun boot_platform() {
            TestApplicationManager.getInstance()
            println("mode=$mode")
            saved_registry = Registry.get(IJENT_REGISTRY_KEY).asBoolean()
            if (mode == "wslexe") {
                Registry.get(IJENT_REGISTRY_KEY).setValue(false)
            } else {
                Registry.get(IJENT_REGISTRY_KEY).setValue(true)
                val binary_dir = Path.of(PathManager.getHomePath(), "plugins", "platform-ijent-impl")
                println("ijent binaries: $binary_dir")
                // The agent session outlives a single test by design and runs on coroutine workers.
                ThreadLeakTracker.longRunningThreadCreated(disposable, "DefaultDispatcher-worker", "grpc-")
                ApplicationManager.getApplication().replaceService(
                    IjentExecFileProvider::class.java,
                    DistributionIjentExecFileProvider(binary_dir),
                    disposable,
                )
            }
            saved_state = SopsSettings.get_instance().state.copy()
            log_dir = Files.createTempDirectory("sops-wsl-e2e-log")
            wsl("mkdir -p ~/.sops-e2e-backup && cp -p $linux_project/secrets/a.yaml ~/.sops-e2e-backup/a.yaml.test")
            profile_checksum = checksum(PROFILE)
            keys_checksum = checksum(KEYS)
            assertEquals(0, wsl("cp -p $PROFILE $PROFILE_BACKUP").first)
        }

        @JvmStatic
        @AfterAll
        fun restore_everything() {
            wsl("test -f ~/k2.txt && mv ~/k2.txt $KEYS; true")
            restore_profile()
            wsl("rm -f $PROFILE_BACKUP")
            wsl("cp -p ~/.sops-e2e-backup/a.yaml.test $linux_project/secrets/a.yaml && rm -f ~/.sops-e2e-backup/a.yaml.test")
            wsl("rm -f $linux_project/secrets/b.env $linux_project/secrets/.sops-editor-tmp-* $linux_project/.sops-editor-tmp-*")
            wsl("rmdir ~/.sops-e2e-backup 2>/dev/null; true")
            SopsSettings.get_instance().loadState(saved_state)
            log_dir.toFile().deleteRecursively()
            Registry.get(IJENT_REGISTRY_KEY).setValue(saved_registry)
            Disposer.dispose(disposable)
            assertEquals(keys_checksum, checksum(KEYS))
        }

        private const val PROFILE = "~/.profile"
        private const val PROFILE_BACKUP = "~/.sops-e2e-backup/profile.test"
        private const val KEYS = "~/.config/sops/age/keys.txt"
        private var profile_checksum = ""
        private var keys_checksum = ""

        // A missing file must fail here: an empty checksum on both sides of a restore check
        // would compare equal and let a lost profile or key pass unnoticed.
        private fun checksum(path: String): String {
            val (exit_code, output) = wsl("set -o pipefail; sha256sum $path | cut -d' ' -f1", login = false)
            assertEquals(0, exit_code, "sha256sum of $path failed: $output")
            val sum = output.trim()
            assertTrue(SHA256_REGEX.matches(sum), "no checksum for $path: $output")
            return sum
        }

        private val SHA256_REGEX = Regex("[0-9a-f]{64}")

        private fun restore_profile() {
            if (checksum(PROFILE) != profile_checksum) {
                wsl("cp -p $PROFILE_BACKUP $PROFILE", login = false)
            }
            assertEquals(profile_checksum, checksum(PROFILE))
        }

        // The profile is shared with the user's real shell, so it is always restored and checked.
        private fun <T> with_profile_lines(vararg lines: String, block: () -> T): T {
            val quoted = lines.joinToString(" ") { "'" + it.replace("'", "'\\''") + "'" }
            assertEquals(0, wsl("printf '%s\\n' $quoted >> $PROFILE").first)
            try {
                return block()
            } finally {
                restore_profile()
            }
        }

        // Checksums run without a login shell so a modified profile cannot add output to them.
        private fun wsl(script: String, login: Boolean = true): Pair<Int, String> {
            val process = ProcessBuilder("wsl.exe", "-d", distro, "--", "bash", if (login) "-lc" else "-c", script)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor(60, TimeUnit.SECONDS)
            return process.exitValue() to output
        }

        private fun query_wsl_home(): String {
            val (exit_code, output) = wsl("printf '%s' \"\$HOME\"", login = false)
            check(exit_code == 0 && output.startsWith("/")) { "Could not read \$HOME from WSL distro $distro" }
            return output.trim()
        }
    }

    // Serves the agent binary shipped with the IDE distribution used by the test runtime.
    private class DistributionIjentExecFileProvider(private val dir: Path) : IjentExecFileProvider {
        override suspend fun getIjentBinary(targetPlatform: IjentPlatform): Path =
            dir.resolve("ijent-x86_64-unknown-linux-musl-release")
    }

    private val settings get() = SopsSettings.get_instance()

    @BeforeEach
    fun neutral_settings() {
        settings.loadState(saved_state.copy())
        // A bogus native path makes any accidental fallback to the Windows branch fail loudly.
        settings.sops_path = "C:\\does-not-exist\\sops-native-must-not-run.exe"
        settings.age_key_file = ""
        settings.custom_environment = ""
        settings.wsl_sops_path = "sops"
        settings.log_path = log_dir.toString()
        SopsLog.clear()
        SopsWrapper.clear_version_cache()
    }

    @AfterEach
    fun ensure_key_restored() {
        wsl("test -f ~/k2.txt && mv ~/k2.txt ~/.config/sops/age/keys.txt; true")
    }

    private fun find(relative: String): VirtualFile {
        val candidates = listOf(
            "$unc_project/$relative",
            "\\\\wsl$\\$distro${linux_project.replace('/', '\\')}\\${relative.replace('/', '\\')}",
        )
        for (candidate in candidates) {
            val file = LocalFileSystem.getInstance().refreshAndFindFileByPath(candidate)
            if (file != null) {
                file.refresh(false, false)
                println("VFS: '$candidate' -> path='${file.path}'")
                return file
            }
            println("VFS: '$candidate' -> null")
        }
        throw AssertionError("VirtualFile not found for $relative")
    }

    private fun report(case: String, result: SopsResult) {
        println("$case: exit=${result.exit_code} stderr=${result.stderr.take(300).replace('\n', ' ')}")
    }

    private fun tmp_leftovers(): String = wsl("ls -A $linux_project/secrets $linux_project | grep sops-editor-tmp || true").second.trim()

    @Test
    @Order(1)
    fun `case 1 decrypt_file`() = runBlocking {
        val file = find("secrets/a.yaml")
        val result = SopsWrapper.decrypt_file(file)
        report("case1", result)
        assertTrue(result.success)
        assertTrue(result.stdout.contains("db_password: $DUMMY_PASSWORD"))
        assertTrue(result.stdout.contains("api_token: $DUMMY_TOKEN"))

        val legacy_path = "\\\\wsl$\\$distro${linux_project.replace('/', '\\')}\\secrets\\a.yaml"
        val legacy = LocalFileSystem.getInstance().refreshAndFindFileByPath(legacy_path)
        println("case1 legacy: '$legacy_path' -> path='${legacy?.path}'")
        assertNotNull(legacy)
        val legacy_result = SopsWrapper.decrypt_file(requireNotNull(legacy))
        report("case1 legacy", legacy_result)
        assertTrue(legacy_result.stdout.contains("db_password: $DUMMY_PASSWORD"))
    }

    @Test
    @Order(2)
    fun `case 2 encrypt_text through stdin and write back`() = runBlocking {
        val file = find("secrets/a.yaml")
        val plain = SopsWrapper.decrypt_file(file).stdout
        val changed = plain.replace("db_password: $DUMMY_PASSWORD", "db_password: hunter3")
        val encrypted = SopsWrapper.encrypt_text(changed, SopsFormat.YAML, file.path)
        report("case2 encrypt", encrypted)
        assertTrue(encrypted.success)
        val log = SopsLog.read()
        assertTrue(log.contains("stdin mode on"))
        assertTrue(log.contains("Executing sops encrypt (running in WSL)"))
        assertTrue(encrypted.stdout.contains("sops:") && encrypted.stdout.contains("ENC["))
        assertFalse(encrypted.stdout.contains("hunter3"))
        Files.writeString(Path.of(file.path), encrypted.stdout)
        file.refresh(false, false)
        val again = SopsWrapper.decrypt_file(file)
        report("case2 decrypt", again)
        assertTrue(again.stdout.contains("db_password: hunter3"))
        val leftovers = tmp_leftovers()
        println("case2: tmp leftovers='$leftovers'")
        assertEquals("", leftovers)
    }

    @Test
    @Order(3)
    fun `case 3 in-place round trip on a new env file`() = runBlocking {
        Files.writeString(Path.of("$unc_project/secrets/b.env"), "E2E_DUMMY=round-trip-value\n")
        val file = find("secrets/b.env")
        val enc = SopsWrapper.encrypt_file_in_place(file)
        report("case3 encrypt", enc)
        assertTrue(enc.success)
        file.refresh(false, false)
        val on_disk = Files.readString(Path.of(file.path))
        assertTrue(on_disk.contains("ENC[") && on_disk.contains("sops_version"))
        val dec = SopsWrapper.decrypt_file_in_place(file)
        report("case3 decrypt", dec)
        assertTrue(dec.success)
        assertEquals("E2E_DUMMY=round-trip-value", Files.readString(Path.of(file.path)).trim())
    }

    @Test
    @Order(4)
    fun `case 4 creation rules are matched on the linux path`() = runBlocking {
        val file = find("outside.yaml")
        val before = Files.readString(Path.of(file.path))
        val result = SopsWrapper.encrypt_file_in_place(file)
        report("case4", result)
        assertFalse(result.success)
        assertTrue(result.stderr.contains("no matching creation rules", ignoreCase = true))
        assertEquals(before, Files.readString(Path.of(file.path)))
    }

    @Test
    @Order(5)
    fun `case 5 custom env is forwarded into WSL`() = runBlocking {
        val file = find("secrets/a.yaml")
        assertEquals(0, wsl("mv ~/.config/sops/age/keys.txt ~/k2.txt").first)
        try {
            settings.custom_environment = "SOPS_AGE_KEY_FILE=$linux_home/k2.txt"
            val with_var = SopsWrapper.decrypt_file(file)
            report("case5 with var", with_var)
            assertTrue(with_var.success)
            settings.custom_environment = ""
            val without_var = SopsWrapper.decrypt_file(file)
            report("case5 without var", without_var)
            assertFalse(without_var.success)
        } finally {
            wsl("mv ~/k2.txt ~/.config/sops/age/keys.txt")
        }
        assertTrue(wsl("test -f ~/.config/sops/age/keys.txt && test ! -e ~/k2.txt").first == 0)
    }

    @Test
    @Order(6)
    fun `case 6 host age key file setting is ignored in WSL`() = runBlocking {
        settings.age_key_file = "C:\\Users\\nobody\\AppData\\Roaming\\sops\\age\\keys.txt"
        val result = SopsWrapper.decrypt_file(find("secrets/a.yaml"))
        report("case6", result)
        assertTrue(result.success)
    }

    @Test
    @Order(7)
    fun `case 7 missing WSL sops yields the not found message`() = runBlocking {
        settings.wsl_sops_path = "sops-missing"
        val result = SopsWrapper.decrypt_file(find("secrets/a.yaml"))
        report("case7", result)
        assertEquals(SopsWslCommand.COMMAND_NOT_FOUND_EXIT_CODE, result.exit_code)
        assertEquals(SopsBundle.message("wsl.sops.not.found"), result.stderr)
    }

    @Test
    @Order(8)
    fun `case 8 the native branch is never taken`() = runBlocking {
        val result = SopsWrapper.decrypt_file(find("secrets/a.yaml"))
        report("case8", result)
        assertTrue(result.success)
        val log = SopsLog.read()
        println("case8 log: ${log.lines().filter { it.contains("sops") }.joinToString(" | ")}")
        assertTrue(log.contains("Executing sops --decrypt (running in WSL)"))
        assertFalse(log.lines().any { it.contains("Executing sops") && !it.contains("(running in WSL)") })
    }

    @Test
    @Order(9)
    fun `case 9 custom env values stay out of the wsl command line`() = runBlocking {
        val marker = "E2E-MARKER-7f3a9c"
        settings.custom_environment = "SOPS_E2E_MARKER=$marker"
        val file = find("secrets/a.yaml")
        val invocation = SopsWslCommand.plan(
            listOf("--decrypt", "--input-type", "yaml", "--output-type", "yaml", file.path),
            file.path,
            file.parent?.path,
        )
        assertNotNull(invocation)
        val method = SopsWrapper::class.java.declaredMethods.first { it.name == "create_wsl_command" }
        method.isAccessible = true
        val command = method.invoke(SopsWrapper, invocation, file.path, settings, SopsWslCommand.new_output_marker(), emptyMap<String, String>()) as GeneralCommandLine
        println("case9 exe='${command.exePath}' cmdline='${command.commandLineString}'")
        println("case9 WSLENV='${command.environment["WSLENV"]}' marker_in_env=${command.environment["SOPS_E2E_MARKER"] == marker}")
        assertFalse(command.commandLineString.contains(marker))
        assertEquals(marker, command.environment["SOPS_E2E_MARKER"])

        // The real run must still succeed with the extra variable.
        val result = SopsWrapper.decrypt_file(file)
        report("case9 run", result)
        assertTrue(result.success)

        // With IJent a long-lived wsl.exe hosts the agent; its command line must stay clean too.
        val ps = ProcessBuilder(
            "powershell.exe", "-NoProfile", "-Command",
            "Get-CimInstance Win32_Process | Where-Object { \$_.Name -like 'wsl*.exe' } | ForEach-Object { \$_.CommandLine }",
        ).redirectErrorStream(true).start()
        val lines = ps.inputStream.bufferedReader().readText().lines().filter { it.isNotBlank() }
        ps.waitFor(60, TimeUnit.SECONDS)
        assertEquals(0, ps.exitValue())
        assertTrue(lines.isNotEmpty())
        println("case9 live wsl processes=${lines.size} marker_in_any=${lines.any { it.contains(marker) }}")
        lines.forEach { println("case9 proc: ${it.take(200)}") }
        assertFalse(lines.any { it.contains(marker) })
    }

    @Test
    @Order(10)
    fun `case 10 profile output stays out of decrypted and encrypted text`() = runBlocking {
        val file = find("secrets/a.yaml")
        val clean = SopsWrapper.decrypt_file(file)
        assertTrue(clean.success)
        with_profile_lines("echo PROFILE-NOISE", "printf 'NOISE-WITHOUT-NEWLINE'") {
            runBlocking {
                val noisy = SopsWrapper.decrypt_file(file)
                report("case10 decrypt", noisy)
                assertTrue(noisy.success)
                assertEquals(clean.stdout, noisy.stdout)

                val encrypted = SopsWrapper.encrypt_text(clean.stdout, SopsFormat.YAML, file.path)
                report("case10 encrypt", encrypted)
                assertTrue(encrypted.success)
                assertFalse(encrypted.stdout.contains("NOISE"))
                assertTrue(encrypted.stdout.startsWith(clean.stdout.substringBefore(':') + ": ENC["))
                Files.writeString(Path.of(file.path), encrypted.stdout)
                file.refresh(false, false)
                val again = SopsWrapper.decrypt_file(file)
                report("case10 decrypt again", again)
                assertTrue(again.success)
                assertEquals(clean.stdout, again.stdout)
            }
        }
        assertEquals("", tmp_leftovers())
    }

    @Test
    @Order(11)
    fun `case 11 variables from the shell profile take precedence over custom env`() = runBlocking {
        val file = find("secrets/a.yaml")
        assertEquals(0, wsl("mv $KEYS ~/k2.txt").first)
        try {
            settings.custom_environment = "SOPS_AGE_KEY_FILE=$linux_home/does-not-exist.txt"
            with_profile_lines("export SOPS_AGE_KEY_FILE=$linux_home/k2.txt") {
                runBlocking {
                    val result = SopsWrapper.decrypt_file(file)
                    report("case11", result)
                    assertTrue(result.success)
                }
            }
        } finally {
            wsl("mv ~/k2.txt $KEYS")
        }
        assertEquals(keys_checksum, checksum(KEYS))
    }

    @Test
    @Order(12)
    fun `case 12 decrypt_text reads ciphertext from stdin`() = runBlocking {
        val file = find("secrets/a.yaml")
        val ciphertext = Files.readString(Path.of(file.path))
        val with_path = SopsWrapper.decrypt_text(ciphertext, SopsFormat.YAML, file.path, null)
        report("case12 with path", with_path)
        assertTrue(with_path.success)
        // Case 2 changed db_password, the token is still the original dummy value.
        assertTrue(with_path.stdout.contains("api_token: $DUMMY_TOKEN"))
        // A revision of a deleted file only has the project as context.
        val with_context = SopsWrapper.decrypt_text(ciphertext, SopsFormat.YAML, null, unc_project)
        report("case12 with context", with_context)
        assertTrue(with_context.success)
        assertTrue(with_context.stdout.contains("api_token: $DUMMY_TOKEN"))
        val log = SopsLog.read()
        assertTrue(log.contains("Executing sops decrypt (running in WSL)"))
        assertFalse(log.lines().any { it.contains("Executing sops") && !it.contains("(running in WSL)") })
        assertEquals("", tmp_leftovers())
    }

    @Test
    @Order(13)
    fun `case 13 sops older than 3_10 falls back to temporary files`() = runBlocking {
        val fake = "$linux_home/.sops-e2e-backup/sops-old.sh"
        // Written through the UNC path, so no shell quoting is involved.
        val script = listOf(
            "#!/bin/sh",
            "if [ \"\$1\" = --version ]; then echo 'sops 3.9.4'; exit 0; fi",
            "exec sops \"\$@\"",
        ).joinToString("\n", postfix = "\n")
        Files.writeString(Path.of("//wsl.localhost/$distro$fake"), script)
        assertEquals(0, wsl("chmod 700 $fake").first)
        try {
            settings.wsl_sops_path = fake
            val file = find("secrets/a.yaml")
            val ciphertext = Files.readString(Path.of(file.path))
            val decrypted = SopsWrapper.decrypt_text(ciphertext, SopsFormat.YAML, file.path, null)
            report("case13 decrypt", decrypted)
            assertTrue(decrypted.success)
            val encrypted = SopsWrapper.encrypt_text(decrypted.stdout, SopsFormat.YAML, file.path)
            report("case13 encrypt", encrypted)
            assertTrue(encrypted.success)
            assertTrue(encrypted.stdout.contains("ENC[") && !encrypted.stdout.contains(DUMMY_PASSWORD))
            val log = SopsLog.read()
            assertTrue(log.contains("Detected sops 3.9.4, stdin mode off"))
            assertTrue(log.contains("Executing sops --encrypt (running in WSL)"))
            assertTrue(log.contains("Executing sops --decrypt (running in WSL)"))
            assertEquals("", tmp_leftovers())
        } finally {
            wsl("rm -f $fake")
        }
    }

    @Test
    @Order(14)
    fun `case 14 cancelling stops sops inside WSL`() {
        val fake = "$linux_home/.sops-e2e-backup/sops-sleep.sh"
        // The odd duration identifies the process; exec keeps the sleep as the sops process itself.
        // The [s] in the patterns below keeps pgrep and pkill from matching their own shell.
        val script = listOf(
            "#!/bin/sh",
            "if [ \"\$1\" = --version ]; then echo 'sops 3.12.0'; exit 0; fi",
            "exec sleep 317",
        ).joinToString("\n", postfix = "\n")
        Files.writeString(Path.of("//wsl.localhost/$distro$fake"), script)
        assertEquals(0, wsl("chmod 700 $fake").first)
        try {
            settings.wsl_sops_path = fake
            val file = find("secrets/a.yaml")
            val indicator = ProgressIndicatorBase()
            val call = ApplicationManager.getApplication().executeOnPooledThread(
                Callable {
                    ProgressManager.getInstance().runProcess(
                        Computable {
                            run_blocking_cancellable(indicator) { SopsWrapper.decrypt_text("x", SopsFormat.YAML, file.path, null) }
                        },
                        indicator,
                    )
                },
            )
            val deadline = System.currentTimeMillis() + 60_000
            while (wsl("pgrep -f '[s]leep 317'", login = false).first != 0) {
                assertTrue(System.currentTimeMillis() < deadline, "fake sops did not start")
                Thread.sleep(200)
            }
            val started = System.currentTimeMillis()
            indicator.cancel()
            assertEquals(null, call.get(30, TimeUnit.SECONDS))
            println("case14: call returned ${System.currentTimeMillis() - started} ms after cancel")
            var alive = true
            val stop_deadline = System.currentTimeMillis() + 10_000
            while (alive && System.currentTimeMillis() < stop_deadline) {
                alive = wsl("pgrep -f '[s]leep 317'", login = false).first == 0
                if (alive) {
                    Thread.sleep(200)
                }
            }
            assertFalse(alive, "sops kept running in WSL after cancel")
            assertTrue(SopsLog.read().contains("sops decrypt was canceled"))

            val timed_out = runBlocking { SopsWrapper.decrypt_text("x", SopsFormat.YAML, file.path, null, timeout_ms = 5_000) }
            report("case14 timeout", timed_out)
            assertEquals(SopsBundle.message("sops.timeout", 5), timed_out.stderr)
            Thread.sleep(1000)
            assertFalse(wsl("pgrep -f '[s]leep 317'", login = false).first == 0, "sops kept running in WSL after the timeout")
        } finally {
            wsl("rm -f $fake; pkill -f '[s]leep 317'; true", login = false)
        }
    }
}
