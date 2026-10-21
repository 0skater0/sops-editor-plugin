package dev.ott.sops.editor

import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.util.ProgressIndicatorBase
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.SystemInfo
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.ott.sops.editor.settings.SopsSettings
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/**
 * Process control and save failure handling against fake sops scripts, so it runs everywhere
 * without a real sops: cancelling and timing out stop the process, a failed write does not leave
 * the editor stuck, and an unknown sops version is probed once.
 */
class SopsProcessControlTest {

    @Test
    fun `cancelling the indicator stops a hanging sops`() = run_case("testCancelStopsProcess")

    @Test
    fun `the timeout stops a hanging sops`() = run_case("testTimeoutStopsProcess")

    @Test
    fun `a failed write reports the error and allows saving again`() = run_case("testWriteFailure")

    @Test
    fun `unknown version without stdin support falls back to a temporary file once`() = run_case("testUnknownVersionRejectsStdin")

    @Test
    fun `unknown version with stdin support keeps using stdin`() = run_case("testUnknownVersionAcceptsStdin")

    @Test
    fun `unknown version without encrypt subcommand never gets the stdin call`() = run_case("testUnknownVersionWithoutSubcommand")

    @Test
    fun `a sops binary that cannot start keeps its path out of the log`() = run_case("testMissingSopsKeepsPathOutOfLog")

    private fun run_case(name: String) {
        val case = Cases()
        case.name = name
        case.runBare()
    }

    class Cases : BasePlatformTestCase() {
        private val settings get() = SopsSettings.get_instance()
        private lateinit var saved: SopsSettings.State
        private lateinit var work_dir: Path
        private val notifications = mutableListOf<Notification>()

        override fun setUp() {
            super.setUp()
            saved = settings.state.copy()
            work_dir = Files.createTempDirectory("sops-process-control")
            Files.createDirectories(work_dir.resolve("secrets"))
            settings.custom_environment = ""
            settings.age_key_file = ""
            settings.log_path = work_dir.resolve("log").toString()
            SopsWrapper.clear_version_cache()
            SopsLog.clear()
            project.messageBus.connect(testRootDisposable).subscribe(
                Notifications.TOPIC,
                object : Notifications {
                    override fun notify(notification: Notification) {
                        notifications.add(notification)
                    }
                },
            )
        }

        override fun tearDown() {
            try {
                settings.loadState(saved)
                SopsWrapper.clear_version_cache()
                work_dir.toFile().deleteRecursively()
            } finally {
                super.tearDown()
            }
        }

        private enum class Behavior { PRINT, SLEEP, REJECT, EDIT_MODE }

        /**
         * Writes a fake sops for the current OS. [version_line] answers `--version`, [help_ok]
         * decides whether `help encrypt` succeeds, [stdin_call] is what the `encrypt` and `decrypt`
         * subcommands do, and the classic `--encrypt` / `--decrypt` flags print TEMP-ROUTE.
         */
        private fun fake_sops(version_line: String, help_ok: Boolean, stdin_call: Behavior): String {
            return if (SystemInfo.isWindows) {
                val stdin_body = when (stdin_call) {
                    Behavior.PRINT -> "echo STDIN-ROUTE\r\nexit /b 0"
                    Behavior.SLEEP -> "ping -n 120 127.0.0.1 >nul\r\nexit /b 0"
                    Behavior.REJECT -> "echo Error: no file specified 1>&2\r\nexit /b 100"
                    Behavior.EDIT_MODE -> "echo EDIT-MODE\r\nexit /b 1"
                }
                val script = listOf(
                    "@echo off",
                    "if \"%~1\"==\"--version\" (",
                    "  echo $version_line",
                    "  exit /b 0",
                    ")",
                    "if \"%~1\"==\"help\" exit /b ${if (help_ok) 0 else 3}",
                    "if \"%~1\"==\"--encrypt\" goto temp",
                    "if \"%~1\"==\"--decrypt\" goto temp",
                    stdin_body,
                    ":temp",
                    "echo TEMP-ROUTE",
                    "exit /b 0",
                ).joinToString("\r\n", postfix = "\r\n")
                val path = work_dir.resolve("fake-sops.cmd")
                Files.writeString(path, script)
                path.toString()
            } else {
                val stdin_body = when (stdin_call) {
                    Behavior.PRINT -> "echo STDIN-ROUTE; exit 0"
                    Behavior.SLEEP -> "sleep 120; exit 0"
                    Behavior.REJECT -> "echo 'Error: no file specified' >&2; exit 100"
                    Behavior.EDIT_MODE -> "echo EDIT-MODE; exit 1"
                }
                val script = listOf(
                    "#!/bin/sh",
                    "case \"\$1\" in",
                    "  --version) echo '$version_line'; exit 0 ;;",
                    "  help) exit ${if (help_ok) 0 else 3} ;;",
                    "  --encrypt|--decrypt) echo TEMP-ROUTE; exit 0 ;;",
                    "esac",
                    stdin_body,
                ).joinToString("\n", postfix = "\n")
                val path = work_dir.resolve("fake-sops.sh")
                Files.writeString(path, script)
                path.toFile().setExecutable(true)
                path.toString()
            }
        }

        private fun target_path(): String = work_dir.resolve("secrets").resolve("a.yaml").toString().replace('\\', '/')

        private fun pump_until(what: String, timeout_ms: Long = 30_000, cond: () -> Boolean) {
            val deadline = System.currentTimeMillis() + timeout_ms
            while (System.currentTimeMillis() < deadline) {
                PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
                if (cond()) {
                    return
                }
                Thread.sleep(20)
            }
            fail("timeout waiting for: $what\nlog:\n${SopsLog.read().takeLast(3000)}")
        }

        private fun descendants(): Set<ProcessHandle> = ProcessHandle.current().descendants().toList().toSet()

        // The sleeping child of the fake sops, found among the processes started by this JVM.
        private fun sleeper(before: Set<ProcessHandle>): List<ProcessHandle> =
            (descendants() - before).filter { handle ->
                val command = handle.info().command().orElse("").lowercase()
                command.endsWith("ping.exe") || command.endsWith("/sleep")
            }

        private fun assert_all_stopped(handles: List<ProcessHandle>) {
            val deadline = System.currentTimeMillis() + 10_000
            while (handles.any { it.isAlive } && System.currentTimeMillis() < deadline) {
                Thread.sleep(50)
            }
            assertTrue("sops process still running after it was stopped", handles.none { it.isAlive })
        }

        fun testCancelStopsProcess() {
            settings.sops_path = fake_sops("sops 3.12.0", help_ok = true, stdin_call = Behavior.SLEEP)
            val before = descendants()
            val indicator = ProgressIndicatorBase()
            val started = System.currentTimeMillis()
            // Same shape as the decrypted diff: a progress task that blocks on a cancellable coroutine.
            val call = ApplicationManager.getApplication().executeOnPooledThread(
                Callable {
                    ProgressManager.getInstance().runProcess(
                        Computable {
                            run_blocking_cancellable(indicator) {
                                SopsWrapper.decrypt_text("ciphertext", SopsFormat.YAML, target_path(), null)
                            }
                        },
                        indicator,
                    )
                },
            )
            var sleeping = emptyList<ProcessHandle>()
            pump_until("fake sops is running") {
                sleeping = sleeper(before)
                sleeping.isNotEmpty()
            }
            indicator.cancel()
            // runProcess turns the cancellation into a null result instead of rethrowing it.
            val outcome = try {
                call.get(20, TimeUnit.SECONDS)?.let { "returned $it" } ?: "canceled"
            } catch (e: ExecutionException) {
                if (e.cause is CancellationException) "canceled" else "failed: ${e.cause}"
            }
            assertEquals("canceled", outcome)
            assertTrue("cancel took too long", System.currentTimeMillis() - started < 30_000)
            assert_all_stopped(sleeping)
            assertTrue(SopsLog.read().contains("was canceled"))
        }

        fun testTimeoutStopsProcess() {
            settings.sops_path = fake_sops("sops 3.12.0", help_ok = true, stdin_call = Behavior.SLEEP)
            val before = descendants()
            val call = ApplicationManager.getApplication().executeOnPooledThread(
                Callable {
                    runBlocking { SopsWrapper.decrypt_text("ciphertext", SopsFormat.YAML, target_path(), null, timeout_ms = 3_000) }
                },
            )
            var sleeping = emptyList<ProcessHandle>()
            pump_until("fake sops is running") {
                sleeping = sleeper(before)
                sleeping.isNotEmpty()
            }
            val result = call.get(30, TimeUnit.SECONDS)
            assertFalse(result.success)
            assertEquals(SopsBundle.message("sops.timeout", 3), result.stderr)
            assert_all_stopped(sleeping)
        }

        fun testWriteFailure() {
            settings.sops_path = fake_sops("sops 3.12.0", help_ok = true, stdin_call = Behavior.PRINT)
            val file = object : LightVirtualFile("a.yaml", "a: 1\n") {
                override fun getOutputStream(requestor: Any?, newModificationStamp: Long, newTimeStamp: Long): OutputStream {
                    throw IOException("File is read-only")
                }
            }
            var not_saved = false
            var saved_content: String? = null
            SopsService.get_instance(project).encrypt_and_save(
                file,
                "a: 2\n",
                "a: 1\n",
                on_not_saved = { not_saved = true },
            ) { saved_content = it }
            pump_until("the save reports the failure") { not_saved }
            assertNull("on_success must not run after a failed write", saved_content)
            val failure = notifications.single { it.content.contains("could not be written") }
            assertEquals(SopsBundle.message("notification.write.failed", "File is read-only"), failure.content)
            assertNull("the retry banner would re-decrypt and drop the edits", SopsService.get_instance(project).get_error(file.path))
        }

        fun testUnknownVersionRejectsStdin() {
            settings.sops_path = fake_sops("custom sops build", help_ok = true, stdin_call = Behavior.REJECT)
            val first = runBlocking { SopsWrapper.encrypt_text("a: 1\n", SopsFormat.YAML, target_path()) }
            assertTrue(first.success)
            assertEquals("TEMP-ROUTE", first.stdout.trim())
            val second = runBlocking { SopsWrapper.decrypt_text("x", SopsFormat.YAML, target_path(), null) }
            assertEquals("TEMP-ROUTE", second.stdout.trim())
            val log = SopsLog.read()
            assertTrue(log.contains("trying stdin first"))
            assertTrue(log.contains("sops cannot read stdin, stdin mode off"))
            assertEquals("probed once", 1, log.lines().count { it.contains("Executing sops --version") })
            assertEquals("stdin tried once", 1, log.lines().count { it.contains("Executing sops encrypt") || it.contains("Executing sops decrypt") })
            assertEquals(emptyList<String>(), Files.list(work_dir.resolve("secrets")).use { it.toList() }.map { it.fileName.toString() })
        }

        fun testUnknownVersionAcceptsStdin() {
            settings.sops_path = fake_sops("custom sops build", help_ok = true, stdin_call = Behavior.PRINT)
            repeat(2) {
                val result = runBlocking { SopsWrapper.encrypt_text("a: 1\n", SopsFormat.YAML, target_path()) }
                assertEquals("STDIN-ROUTE", result.stdout.trim())
            }
            val log = SopsLog.read()
            assertTrue(log.contains("sops read stdin, stdin mode on"))
            assertEquals(1, log.lines().count { it.contains("Executing sops --version") })
            assertEquals(1, log.lines().count { it.contains("Executing sops help") })
        }

        fun testUnknownVersionWithoutSubcommand() {
            settings.sops_path = fake_sops("custom sops build", help_ok = false, stdin_call = Behavior.EDIT_MODE)
            val result = runBlocking { SopsWrapper.encrypt_text("a: 1\n", SopsFormat.YAML, target_path()) }
            assertEquals("TEMP-ROUTE", result.stdout.trim())
            val log = SopsLog.read()
            assertTrue(log.contains("Could not detect the sops version, using temporary files"))
            assertFalse(log.contains("Executing sops encrypt"))
        }

        fun testMissingSopsKeepsPathOutOfLog() {
            val missing_dir = "missing-sops-dir-4711"
            settings.sops_path = work_dir.resolve(missing_dir).resolve(if (SystemInfo.isWindows) "sops.exe" else "sops").toString()
            val file = LightVirtualFile("missing-sops.yaml", "a: ENC[AES256_GCM,data:x,type:str]\nsops:\n    mac: ENC[AES256_GCM,data:y,type:str]\n")
            val service = SopsService.get_instance(project)
            service.decrypt(file) { fail("decrypt must fail without a sops binary") }
            pump_until("decrypt failure") { SopsLog.read().contains("SOPS decrypt failed") }
            var not_saved = false
            service.encrypt_and_save(file, "a: 2\n", "a: 1\n", on_not_saved = { not_saved = true }) {
                fail("encrypt must fail without a sops binary")
            }
            pump_until("encrypt failure") { not_saved && SopsLog.read().contains("SOPS encrypt failed") }

            val log = SopsLog.read()
            assertTrue(log, log.contains("sops could not be started: "))
            assertFalse("the executable path must not reach the log: $log", log.contains(missing_dir))
            // The notifications keep the full message, the user needs it to fix the path.
            assertTrue(notifications.any { it.content.contains(missing_dir) })
            // The light test project and its services are shared between the cases.
            service.clear_error(file.path)
        }
    }
}
