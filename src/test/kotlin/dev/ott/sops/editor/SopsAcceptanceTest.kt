package dev.ott.sops.editor

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManagerEx
import com.intellij.diff.DiffTool
import com.intellij.diff.EditorDiffViewer
import com.intellij.diff.FrameDiffTool
import com.intellij.diff.impl.DiffRequestProcessor
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.tools.simple.SimpleDiffTool
import com.intellij.diff.util.DiffUserDataKeysEx
import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.DialogWrapperPeer
import com.intellij.openapi.ui.DialogWrapperPeerFactory
import com.intellij.openapi.ui.impl.DialogWrapperPeerFactoryImpl
import com.intellij.openapi.ui.impl.DialogWrapperPeerImpl
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.platform.ijent.IjentExecFileProvider
import com.intellij.platform.ijent.IjentPlatform
import com.intellij.testFramework.FileEditorManagerTestCase
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.common.ThreadLeakTracker
import com.intellij.testFramework.replaceService
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.UiInterceptors
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.UIUtil
import com.intellij.vcsUtil.VcsUtil
import dev.ott.sops.editor.diff.SopsDecryptedDiffTool
import dev.ott.sops.editor.diff.SopsDecryptedDiffViewer
import dev.ott.sops.editor.settings.SopsSettings
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JComboBox
import javax.swing.JLabel

/**
 * Editor-level acceptance test against a real sops, opt-in via SOPS_ACCEPT=1. It builds its own
 * throwaway fixture (a git repository with a `.sops.yaml` and an encrypted dummy `secrets/a.yaml`
 * committed twice) and removes it at the end, so it never touches an existing project.
 *
 * SOPS_ACCEPT_TARGET picks the route:
 * - `native`: needs SOPS_ACCEPT_NATIVE_DIR (scratch directory outside the repository) and APPDATA
 *   pointing inside it, so sops never sees the real Windows age key. A fresh test key is generated
 *   with age-keygen. SOPS_ACCEPT_SOPS and SOPS_ACCEPT_AGE_KEYGEN override the binaries (default
 *   `C:\tools\sops.exe` and `C:\tools\age-keygen.exe`).
 * - `wsl`: needs the prepared distribution of [SopsWslIntegrationTest] (sops, git and the default
 *   test age key; the recipient is taken from `~/sops-e2e/.sops.yaml`). The fixture lives in
 *   `~/sops-accept-<timestamp>`. SOPS_WSL_E2E_MODE picks `ijent` (default) or `wslexe`.
 *
 * Run with `--rerun`, Gradle does not track these variables as task inputs.
 */
@EnabledOnOs(OS.WINDOWS)
@EnabledIfEnvironmentVariable(named = "SOPS_ACCEPT", matches = "1")
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SopsAcceptanceTest {

    @Test
    @Order(1)
    fun `stdin round trip without temporary files`() = run_case("testStdinRoundTrip")

    @Test
    @Order(2)
    fun `old sops falls back to temporary files`() = run_case("testOldSopsFallback")

    @Test
    @Order(3)
    fun `manual encrypt action with auto encrypt off`() = run_case("testManualEncryptAction")

    @Test
    @Order(4)
    fun `new file failure leaves no plaintext`() = run_case("testNewFile")

    @Test
    @Order(5)
    fun `decrypted diff of two revisions`() = run_case("testDecryptedDiff")

    @Test
    @Order(6)
    fun `remove fixture`() = run_case("testRemoveFixture")

    private fun run_case(name: String) {
        assumeTrue(target == "native" || target == "wsl", "SOPS_ACCEPT_TARGET must be native or wsl")
        val case = Cases()
        case.name = name
        case.runBare()
    }

    companion object {
        private val target = System.getenv("SOPS_ACCEPT_TARGET") ?: ""
        private val mode = System.getenv("SOPS_WSL_E2E_MODE") ?: "ijent"
        private val distro = System.getenv("SOPS_WSL_E2E_DISTRO") ?: "Ubuntu-24.04"
        private val native_sops = System.getenv("SOPS_ACCEPT_SOPS") ?: "C:\\tools\\sops.exe"
        private val native_keygen = System.getenv("SOPS_ACCEPT_AGE_KEYGEN") ?: "C:\\tools\\age-keygen.exe"
        private val run_id = System.currentTimeMillis().toString()
        private const val IGNORE_HEADLESS = "intellij.progress.task.ignoreHeadless"

        const val REV1_PASSWORD = "rev1-pass-71"
        const val REV1_TOKEN = "rev1-token-71"
        const val REV2_PASSWORD = "rev2-pass-82"
        const val REV2_TOKEN = "rev2-token-82"

        // Created once per JVM by the first case and removed by the last one.
        private var fixture: Fixture? = null

        class Fixture(
            val repo: String,
            val key_file: String?,
            val cleanup: () -> Unit,
            val git_show: (String) -> ByteArray,
        )

        fun run(command: List<String>, dir: Path? = null, stdin: String? = null): Pair<Int, String> {
            val builder = ProcessBuilder(command).redirectErrorStream(true)
            if (dir != null) {
                builder.directory(dir.toFile())
            }
            val process = builder.start()
            process.outputStream.use { stream -> stdin?.let { stream.write(it.toByteArray()) } }
            val output = process.inputStream.readAllBytes()
            process.waitFor(120, TimeUnit.SECONDS)
            return process.exitValue() to String(output)
        }

        fun wsl(script: String): Pair<Int, String> {
            return run(listOf("wsl.exe", "-d", distro, "--", "bash", "-lc", script))
        }

        fun plain_yaml(password: String, token: String) = "db_password: $password\napi_token: $token\n"

        private fun create_native_fixture(): Fixture {
            val base = System.getenv("SOPS_ACCEPT_NATIVE_DIR") ?: error("SOPS_ACCEPT_NATIVE_DIR is not set")
            val appdata = System.getenv("APPDATA") ?: ""
            check(Path.of(appdata).toAbsolutePath().startsWith(Path.of(base).toAbsolutePath())) {
                "APPDATA must point inside SOPS_ACCEPT_NATIVE_DIR so the real age key stays untouched"
            }
            val root = Path.of(base, "accept-$run_id")
            val repo = root.resolve("repo")
            Files.createDirectories(repo.resolve("secrets"))
            val key_file = root.resolve("keys.txt")
            check(run(listOf(native_keygen, "-o", key_file.toString())).first == 0)
            val recipient = Files.readAllLines(key_file).first { it.startsWith("# public key: ") }.removePrefix("# public key: ").trim()
            // Windows sops matches path_regex against backslash paths.
            Files.writeString(repo.resolve(".sops.yaml"), "creation_rules:\n  - path_regex: secrets[\\\\/].*\\.(yaml|env)$\n    age: $recipient\n")
            val git = listOf("git", "-c", "user.name=accept", "-c", "user.email=accept@example.invalid", "-c", "core.autocrlf=false")
            check(run(git + listOf("init", "-q"), repo).first == 0)
            for ((password, token) in listOf(REV1_PASSWORD to REV1_TOKEN, REV2_PASSWORD to REV2_TOKEN)) {
                val (code, cipher) = run(
                    listOf(native_sops, "encrypt", "--filename-override", "secrets/a.yaml", "--input-type", "yaml", "--output-type", "yaml"),
                    repo,
                    plain_yaml(password, token),
                )
                check(code == 0) { "fixture encrypt failed: $cipher" }
                Files.writeString(repo.resolve("secrets/a.yaml"), cipher)
                check(run(git + listOf("add", "."), repo).first == 0)
                check(run(git + listOf("commit", "-q", "-m", "rev"), repo).first == 0)
            }
            return Fixture(
                repo = repo.toString().replace('\\', '/'),
                key_file = key_file.toString(),
                cleanup = { root.toFile().deleteRecursively() },
                git_show = { rev -> run_bytes(git + listOf("show", "$rev:secrets/a.yaml"), repo) },
            )
        }

        private fun create_wsl_fixture(): Fixture {
            val (home_code, home) = wsl("printf '%s' \"\$HOME\"")
            check(home_code == 0)
            val linux_root = "${home.trim()}/sops-accept-$run_id"
            val repo = "$linux_root/repo"
            val git = "git -c user.name=accept -c user.email=accept@example.invalid"
            val steps = StringBuilder()
            steps.append("set -e; mkdir -p $repo/secrets; cd $repo; cp ~/sops-e2e/.sops.yaml .; $git init -q; ")
            for ((password, token) in listOf(REV1_PASSWORD to REV1_TOKEN, REV2_PASSWORD to REV2_TOKEN)) {
                steps.append("printf '%s\\n' 'db_password: $password' 'api_token: $token' | sops encrypt --filename-override secrets/a.yaml --input-type yaml --output-type yaml > secrets/a.yaml; ")
                steps.append("$git add .; $git commit -q -m rev; ")
            }
            val (code, output) = wsl(steps.toString())
            check(code == 0) { "WSL fixture failed: $output" }
            return Fixture(
                repo = "//wsl.localhost/$distro$repo",
                key_file = null,
                cleanup = { wsl("rm -rf $linux_root") },
                git_show = { rev -> run_bytes(listOf("wsl.exe", "-d", distro, "--cd", repo, "--", "git", "show", "$rev:secrets/a.yaml"), null) },
            )
        }

        private fun run_bytes(command: List<String>, dir: Path?): ByteArray {
            val builder = ProcessBuilder(command)
            if (dir != null) {
                builder.directory(dir.toFile())
            }
            val process = builder.start()
            val bytes = process.inputStream.readAllBytes()
            process.waitFor(60, TimeUnit.SECONDS)
            check(process.exitValue() == 0)
            return bytes
        }

        fun fixture(): Fixture {
            fixture?.let { return it }
            val created = if (target == "native") create_native_fixture() else create_wsl_fixture()
            fixture = created
            println("[accept] target=$target mode=$mode fixture ready")
            return created
        }

        fun remove_fixture() {
            fixture?.cleanup?.invoke()
            fixture = null
        }
    }

    // Unit-test mode builds a HeadlessDialog that is never modal, so showAndGet() would throw.
    // This peer only reports modality; the UiInterceptor answers the dialog before it shows.
    private class ModalPeer : DialogWrapperPeerImpl {
        constructor(w: DialogWrapper, p: Project?, c: Boolean, t: DialogWrapper.IdeModalityType) : super(w, p, c, t)
        constructor(w: DialogWrapper, p: Project?, c: Boolean) : super(w, p, c)
        constructor(w: DialogWrapper, c: Boolean) : super(w, c)

        override fun isModal(): Boolean = true
    }

    class ModalPeerFactory : DialogWrapperPeerFactoryImpl() {
        override fun createPeer(wrapper: DialogWrapper, project: Project?, canBeParent: Boolean, ideModalityType: DialogWrapper.IdeModalityType): DialogWrapperPeer =
            ModalPeer(wrapper, project, canBeParent, ideModalityType)

        override fun createPeer(wrapper: DialogWrapper, project: Project?, canBeParent: Boolean): DialogWrapperPeer =
            ModalPeer(wrapper, project, canBeParent)

        override fun createPeer(wrapper: DialogWrapper, canBeParent: Boolean): DialogWrapperPeer =
            ModalPeer(wrapper, canBeParent)
    }

    private class DistributionIjentExecFileProvider(private val dir: Path) : IjentExecFileProvider {
        override suspend fun getIjentBinary(targetPlatform: IjentPlatform): Path =
            dir.resolve("ijent-x86_64-unknown-linux-musl-release")
    }

    // Serves the diff request the test hands in, like the processors of the VCS diff windows.
    private class TestDiffProcessor(project: Project, private val request: DiffRequest) : DiffRequestProcessor(project) {
        override fun updateRequest(force: Boolean, scrollToChangePolicy: DiffUserDataKeysEx.ScrollToPolicy?) {
            applyRequest(request, force, scrollToChangePolicy)
        }

        // Same entry point as the viewer switcher in the diff toolbar.
        fun switch_to(tool: DiffTool) {
            val method = DiffRequestProcessor::class.java.getDeclaredMethod("switchToDiffTool", DiffTool::class.java)
            method.isAccessible = true
            method.invoke(this, tool)
        }
    }

    class Cases : FileEditorManagerTestCase() {
        private val settings get() = SopsSettings.get_instance()
        private lateinit var saved: SopsSettings.State
        private val notifications = mutableListOf<Notification>()
        private lateinit var log_dir: Path

        override fun setUp() {
            super.setUp()
            println("[accept] case=$name target=$target mode=$mode")
            if (target == "wsl") {
                // Test mode restricts VFS access to known roots; the fixture lives on the WSL share.
                VfsRootAccess.allowRootAccess(testRootDisposable, "//wsl.localhost/$distro", "//wsl$/$distro")
                if (mode == "wslexe") {
                    Registry.get("wsl.use.remote.agent.for.launch.processes").setValue(false, testRootDisposable)
                } else {
                    Registry.get("wsl.use.remote.agent.for.launch.processes").setValue(true, testRootDisposable)
                    ThreadLeakTracker.longRunningThreadCreated(ApplicationManager.getApplication(), "DefaultDispatcher-worker", "grpc-")
                    ApplicationManager.getApplication().replaceService(
                        IjentExecFileProvider::class.java,
                        DistributionIjentExecFileProvider(Path.of(PathManager.getHomePath(), "plugins", "platform-ijent-impl")),
                        testRootDisposable,
                    )
                }
            }
            saved = settings.state.copy()
            log_dir = Files.createTempDirectory("sops-accept-log")
            settings.custom_environment = ""
            settings.wsl_sops_path = "sops"
            settings.auto_encrypt_on_save = true
            settings.encrypted_side_editable = false
            settings.show_decrypted_diff = true
            settings.log_path = log_dir.toString()
            val fixture = fixture()
            if (target == "native") {
                settings.sops_path = native_sops
                settings.age_key_file = fixture.key_file ?: ""
            } else {
                // A bogus native path makes any accidental fallback to the Windows branch fail loudly.
                settings.sops_path = "C:\\does-not-exist\\sops-native-must-not-run.exe"
                settings.age_key_file = ""
            }
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
                UiInterceptors.clear()
                settings.loadState(saved)
                SopsWrapper.clear_version_cache()
                log_dir.toFile().deleteRecursively()
            } finally {
                super.tearDown()
            }
        }

        private val repo get() = fixture().repo

        private fun pump_until(what: String, timeout_ms: Long = 60_000, cond: () -> Boolean) {
            val deadline = System.currentTimeMillis() + timeout_ms
            while (System.currentTimeMillis() < deadline) {
                PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
                if (cond()) {
                    return
                }
                Thread.sleep(50)
            }
            fail("timeout waiting for: $what\nlog:\n${SopsLog.read().takeLast(3000)}")
        }

        private fun pump_for(ms: Long) {
            val deadline = System.currentTimeMillis() + ms
            while (System.currentTimeMillis() < deadline) {
                PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
                Thread.sleep(50)
            }
        }

        private fun vfile(path: String): VirtualFile {
            val file = LocalFileSystem.getInstance().refreshAndFindFileByPath(path)
            assertNotNull("VFS cannot find the fixture file", file)
            val found = requireNotNull(file)
            found.refresh(false, false)
            return found
        }

        private fun disk(path: String): String = Files.readString(Path.of(path))

        private fun sha(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

        private fun log_has_no_native_call() {
            if (target == "wsl") {
                assertFalse(SopsLog.read().lines().any { it.contains("Executing sops") && !it.contains("(running in WSL)") })
            }
        }

        private fun tmp_names(dir: String): List<String> =
            Files.list(Path.of(dir)).use { stream -> stream.map { it.fileName.toString() }.filter { it.contains("sops-editor-tmp") }.toList() }

        /**
         * Polls the directories while [block] runs and reports every plugin temp file it saw.
         * Stdin mode must never create one, not even for a moment.
         */
        private fun <T> watch_temp_files(dirs: List<String>, block: () -> T): Pair<T, Set<String>> {
            val seen = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
            val running = AtomicBoolean(true)
            val watcher = Thread {
                while (running.get()) {
                    for (dir in dirs) {
                        try {
                            Files.list(Path.of(dir)).use { stream ->
                                stream.map { it.fileName.toString() }
                                    .filter { it.startsWith(".sops-editor-tmp-") || (it.startsWith("sops-editor-") && it != "sops-editor-plugin") }
                                    .forEach { seen.add(it) }
                            }
                        } catch (_: Exception) {
                        }
                    }
                    Thread.sleep(2)
                }
            }
            watcher.start()
            try {
                return block() to seen
            } finally {
                running.set(false)
                watcher.join()
            }
        }

        fun testStdinRoundTrip() {
            val target_path = "$repo/secrets/stdin-check.yaml"
            val plain = plain_yaml("stdin-pass-1", "stdin-token-1")
            val watched = listOf("$repo/secrets", System.getProperty("java.io.tmpdir"))
            val (results, seen) = watch_temp_files(watched) {
                runBlocking {
                    val encrypted = SopsWrapper.encrypt_text(plain, SopsFormat.YAML, target_path)
                    assertTrue("encrypt failed: ${encrypted.stderr}", encrypted.success)
                    val decrypted = SopsWrapper.decrypt_text(encrypted.stdout, SopsFormat.YAML, target_path, null)
                    encrypted to decrypted
                }
            }
            val (encrypted, decrypted) = results
            assertTrue(encrypted.stdout.contains("ENC[") && !encrypted.stdout.contains("stdin-pass-1"))
            assertTrue("decrypt failed: ${decrypted.stderr}", decrypted.success)
            assertEquals(plain, decrypted.stdout.replace("\r\n", "\n"))
            assertFalse(Files.exists(Path.of(target_path)))
            assertEquals("no temp file may appear in stdin mode", emptySet<String>(), seen)
            val log = SopsLog.read()
            assertTrue(log.contains("stdin mode on"))
            assertTrue(log.contains("Executing sops encrypt"))
            assertTrue(log.contains("Executing sops decrypt"))
            log_has_no_native_call()
            println("[accept] stdin round trip ok, temp files seen: ${seen.size}")
        }

        fun testOldSopsFallback() {
            if (target == "native") {
                val wrapper = Path.of(requireNotNull(fixture().key_file)).resolveSibling("sops-old.cmd")
                Files.writeString(
                    wrapper,
                    "@echo off\r\nif \"%~1\"==\"--version\" (\r\n  echo sops 3.9.4\r\n  exit /b 0\r\n)\r\n\"$native_sops\" %*\r\n",
                )
                settings.sops_path = wrapper.toString()
            } else {
                val linux_wrapper = repo.substringAfter(distro).substringBeforeLast("/repo") + "/sops-old.sh"
                Files.writeString(
                    Path.of("//wsl.localhost/$distro$linux_wrapper"),
                    "#!/bin/sh\nif [ \"\$1\" = --version ]; then echo 'sops 3.9.4'; exit 0; fi\nexec sops \"\$@\"\n",
                )
                assertEquals(0, wsl("chmod 700 $linux_wrapper").first)
                settings.wsl_sops_path = linux_wrapper
            }
            val target_path = "$repo/secrets/fallback-check.yaml"
            val plain = plain_yaml("fallback-pass-1", "fallback-token-1")
            val encrypted = runBlocking { SopsWrapper.encrypt_text(plain, SopsFormat.YAML, target_path) }
            assertTrue("encrypt failed: ${encrypted.stderr}", encrypted.success)
            assertFalse(encrypted.stdout.contains("fallback-pass-1"))
            val decrypted = runBlocking { SopsWrapper.decrypt_text(encrypted.stdout, SopsFormat.YAML, target_path, null) }
            assertTrue("decrypt failed: ${decrypted.stderr}", decrypted.success)
            assertEquals(plain, decrypted.stdout.replace("\r\n", "\n"))
            val log = SopsLog.read()
            assertTrue(log.contains("Detected sops 3.9.4, stdin mode off"))
            assertTrue(log.contains("Executing sops --encrypt"))
            assertTrue(log.contains("Executing sops --decrypt"))
            assertEquals(emptyList<String>(), tmp_names("$repo/secrets"))
            log_has_no_native_call()
            println("[accept] old sops fallback ok")
        }

        private fun sops_editor_for(file: VirtualFile): SopsEditor? =
            FileEditorManager.getInstance(project).getAllEditors(file).filterIsInstance<SopsEditor>().firstOrNull()

        private fun encrypt_action(): AnAction =
            requireNotNull(ActionManager.getInstance().getAction("dev.ott.sops.editor.SopsEncryptAction"))

        @Suppress("DEPRECATION")
        private fun event(place: String, vararg data: Pair<com.intellij.openapi.actionSystem.DataKey<*>, Any>): AnActionEvent {
            val builder = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project)
            for ((key, value) in data) {
                @Suppress("UNCHECKED_CAST")
                builder.add(key as com.intellij.openapi.actionSystem.DataKey<Any>, value)
            }
            return AnActionEvent.createFromDataContext(place, null, builder.build())
        }

        private fun updated(event: AnActionEvent): AnActionEvent {
            encrypt_action().update(event)
            return event
        }

        fun testManualEncryptAction() {
            val path = "$repo/secrets/a.yaml"
            val file = vfile(path)
            val editor_list = FileEditorManager.getInstance(project).openFile(file, true)
            println("[accept] openFile -> ${editor_list.map { it.javaClass.simpleName }}")
            pump_until("split editor") { sops_editor_for(file) != null }
            val editor = requireNotNull(sops_editor_for(file))
            val document = editor.textEditor.editor.document
            pump_until("decrypted text") { document.text.contains("db_password: $REV2_PASSWORD") }

            val editor_context = arrayOf<Pair<com.intellij.openapi.actionSystem.DataKey<*>, Any>>(
                PlatformCoreDataKeys.FILE_EDITOR to editor,
                CommonDataKeys.VIRTUAL_FILE to editor.file,
            )
            val pane_context = arrayOf<Pair<com.intellij.openapi.actionSystem.DataKey<*>, Any>>(CommonDataKeys.VIRTUAL_FILE to editor.file)
            val unchanged = updated(event(ActionPlaces.EDITOR_POPUP, *editor_context))
            assertTrue(unchanged.presentation.isVisible)
            assertFalse("nothing to save yet", unchanged.presentation.isEnabled)
            assertFalse(
                "project view keeps hiding Encrypt on a SOPS file",
                updated(event(ActionPlaces.PROJECT_VIEW_POPUP, CommonDataKeys.VIRTUAL_FILE to file)).presentation.isVisible,
            )

            settings.auto_encrypt_on_save = false
            val before = disk(path)
            WriteCommandAction.runWriteCommandAction(project) {
                document.setText(document.text.replace("db_password: $REV2_PASSWORD", "db_password: manual-save-1"))
            }
            FileDocumentManager.getInstance().saveAllDocuments()
            pump_for(2000)
            assertEquals("auto re-encrypt off: Ctrl+S must not write", sha(before), sha(disk(path)))
            assertTrue(editor.is_modified)

            val pane_event = updated(event(ActionPlaces.EDITOR_POPUP, *pane_context))
            assertTrue(pane_event.presentation.isEnabledAndVisible)
            val tab_event = updated(event(ActionPlaces.EDITOR_TAB_POPUP, CommonDataKeys.VIRTUAL_FILE to file))
            assertTrue(tab_event.presentation.isEnabledAndVisible)
            val run_event = updated(event(ActionPlaces.EDITOR_POPUP, *editor_context))
            assertTrue(run_event.presentation.isEnabledAndVisible)

            val (_, seen) = watch_temp_files(listOf("$repo/secrets")) {
                encrypt_action().actionPerformed(run_event)
                pump_until("manual encrypt wrote the file") { sha(disk(path)) != sha(before) && !editor.is_modified }
            }
            val after = disk(path)
            assertTrue(after.contains("ENC["))
            assertFalse(after.contains("manual-save-1"))
            assertEquals(emptySet<String>(), seen)
            file.refresh(false, false)
            val again = runBlocking { SopsWrapper.decrypt_file(file) }
            assertTrue("decrypt failed: ${again.stderr}", again.success)
            assertTrue(again.stdout.contains("db_password: manual-save-1"))
            assertTrue(again.stdout.contains("api_token: $REV2_TOKEN"))
            assertFalse(updated(event(ActionPlaces.EDITOR_POPUP, *editor_context)).presentation.isEnabled)
            log_has_no_native_call()
            println("[accept] manual Encrypt with SOPS saved with auto re-encrypt off")
            FileEditorManager.getInstance(project).closeFile(file)
        }

        private fun run_new_file_action(dir: VirtualFile, file_name: String, format: SopsFormat) {
            var intercepted = false
            ApplicationManager.getApplication().replaceService(DialogWrapperPeerFactory::class.java, ModalPeerFactory(), testRootDisposable)
            UiInterceptors.register(object : UiInterceptors.UiInterceptor<DialogWrapper>(DialogWrapper::class.java) {
                override fun doIntercept(component: DialogWrapper) {
                    intercepted = true
                    val combo = component.javaClass.getDeclaredField("format_combo").apply { isAccessible = true }.get(component) as JComboBox<*>
                    combo.selectedIndex = format.ordinal
                    val field = component.javaClass.getDeclaredField("name_field").apply { isAccessible = true }.get(component) as JBTextField
                    field.text = file_name
                    component.close(DialogWrapper.OK_EXIT_CODE)
                }
            })
            val action = requireNotNull(ActionManager.getInstance().getAction("dev.ott.sops.editor.SopsNewFileAction"))
            val event = event(ActionPlaces.PROJECT_VIEW_POPUP, CommonDataKeys.VIRTUAL_FILE to dir)
            action.update(event)
            assertTrue(event.presentation.isEnabledAndVisible)
            action.actionPerformed(event)
            assertTrue("dialog was not shown", intercepted)
        }

        fun testNewFile() {
            // The repository root matches no creation rule, so encryption fails there.
            val root = vfile(repo)
            val (_, seen_failure) = watch_temp_files(listOf(repo)) {
                run_new_file_action(root, "accept-fail.yaml", SopsFormat.YAML)
                pump_until("failure notification") { notifications.any { it.content.contains("was not created") } }
            }
            val failure = notifications.first { it.content.contains("was not created") }
            println("[accept] new file failure notification: ${failure.title} / ${failure.content.take(160)}")
            assertTrue(failure.content.contains("no matching creation rules", ignoreCase = true))
            pump_for(1000)
            assertFalse("no file may remain after a failed encryption", Files.exists(Path.of("$repo/accept-fail.yaml")))
            assertEquals(emptySet<String>(), seen_failure)
            assertEquals(emptyList<String>(), tmp_names(repo))

            val secrets = vfile("$repo/secrets")
            run_new_file_action(secrets, "accept-new.env", SopsFormat.DOTENV)
            val created_path = "$repo/secrets/accept-new.env"
            pump_until("new file created and opened") {
                val file = LocalFileSystem.getInstance().findFileByPath(created_path)
                file != null && sops_editor_for(file) != null
            }
            val content = disk(created_path)
            assertTrue(content.contains("ENC[") && content.contains("sops_version="))
            assertFalse(content.contains("SECRET_KEY=value"))
            val file = vfile(created_path)
            val editor = requireNotNull(sops_editor_for(file))
            pump_until("template decrypted") { editor.textEditor.editor.document.text.contains("SECRET_KEY=value") }
            log_has_no_native_call()
            println("[accept] new file: failure leaves nothing, success writes only ciphertext")
            FileEditorManager.getInstance(project).closeFile(file)
            WriteCommandAction.runWriteCommandAction(project) { file.delete(this) }
            assertFalse(Files.exists(Path.of(created_path)))
        }

        private fun diff_request(): DiffRequest {
            val factory = DiffContentFactory.getInstance()
            val file_path = VcsUtil.getFilePath("$repo/secrets/a.yaml", false)
            val left = factory.createFromBytes(project, fixture().git_show("HEAD~1"), file_path)
            val right = factory.createFromBytes(project, fixture().git_show("HEAD"), file_path)
            return SimpleDiffRequest("a.yaml history", left, right, "HEAD~1", "HEAD")
        }

        private fun editor_texts(processor: DiffRequestProcessor): List<String> {
            val viewer = processor.activeViewer as? EditorDiffViewer ?: return emptyList()
            return viewer.editors.map { it.document.text }
        }

        private fun label_texts(processor: DiffRequestProcessor): List<String> =
            UIUtil.findComponentsOfType(processor.component, JLabel::class.java).mapNotNull { it.text }

        private fun notice_texts(processor: DiffRequestProcessor): List<String> =
            UIUtil.findComponentsOfType(processor.component, EditorNotificationPanel::class.java).map { it.text }

        private fun fitted_tool_names(processor: DiffRequestProcessor, request: DiffRequest): List<String> =
            DiffManagerEx.getInstance().diffTools
                .filter { it is FrameDiffTool && it.canShow(processor.context, request) }
                .map { it.name }

        fun testDecryptedDiff() {
            // Test mode runs backgroundable tasks synchronously on the calling thread; this keeps
            // them asynchronous as in the IDE, so the test can see that the EDT is not blocked.
            val previous = System.setProperty(IGNORE_HEADLESS, "true")
            try {
                check_decrypted_diff()
            } finally {
                if (previous == null) {
                    System.clearProperty(IGNORE_HEADLESS)
                } else {
                    System.setProperty(IGNORE_HEADLESS, previous)
                }
            }
        }

        private fun check_decrypted_diff() {
            val request = diff_request()
            val processor = TestDiffProcessor(project, request)
            Disposer.register(testRootDisposable, processor)
            val sops_tool = requireNotNull(DiffManagerEx.getInstance().diffTools.firstOrNull { it is SopsDecryptedDiffTool })

            processor.updateRequest()
            assertTrue(
                "decrypted viewer must be the default for SOPS diffs, got ${processor.activeViewer?.javaClass?.simpleName}",
                processor.activeViewer is SopsDecryptedDiffViewer,
            )
            // Decryption runs in the background, so right after the request the viewer shows the placeholder.
            val placeholders = editor_texts(processor)
            assertTrue("placeholders: $placeholders", placeholders.size == 2 && placeholders.all { it == SopsBundle.message("diff.decrypting") })
            pump_until("decrypted revisions") {
                val texts = editor_texts(processor)
                texts.size == 2 && texts[0].contains("db_password: $REV1_PASSWORD") && texts[1].contains("db_password: $REV2_PASSWORD")
            }
            val decrypted = editor_texts(processor)
            assertTrue(decrypted[0].contains("api_token: $REV1_TOKEN") && decrypted[1].contains("api_token: $REV2_TOKEN"))
            assertFalse(decrypted.any { it.contains("ENC[") || it.contains("sops:") })
            val labels = label_texts(processor)
            println("[accept] diff labels: ${labels.filter { it.isNotBlank() }.take(12)}")
            assertTrue(labels.any { it.contains("HEAD~1 (decrypted)") })
            assertTrue(labels.any { it.contains("HEAD (decrypted)") })
            assertTrue(notice_texts(processor).any { it.contains("Decrypted view, read-only") })
            val tools = fitted_tool_names(processor, request)
            println("[accept] viewers offered: $tools")
            assertTrue(tools.contains(SopsBundle.message("diff.tool.name")))
            assertTrue(tools.contains(SimpleDiffTool.INSTANCE.name))
            val processor_editors = (processor.activeViewer as EditorDiffViewer).editors
            assertTrue(processor_editors.all { it.isViewer })

            processor.switch_to(SimpleDiffTool.INSTANCE)
            pump_for(300)
            assertFalse(processor.activeViewer is SopsDecryptedDiffViewer)
            val ciphertext = editor_texts(processor)
            assertTrue(ciphertext.size == 2 && ciphertext.all { it.contains("ENC[") })
            assertFalse(ciphertext.any { it.contains(REV1_PASSWORD) || it.contains(REV2_PASSWORD) })
            println("[accept] switched to ${SimpleDiffTool.INSTANCE.name}: ciphertext shown")

            processor.switch_to(sops_tool)
            pump_until("decrypted again") { editor_texts(processor).getOrNull(1)?.contains(REV2_PASSWORD) == true }
            println("[accept] switched back to ${sops_tool.name}: plaintext shown")

            settings.show_decrypted_diff = false
            processor.updateRequest(true)
            pump_for(300)
            assertFalse(processor.activeViewer is SopsDecryptedDiffViewer)
            assertFalse(fitted_tool_names(processor, request).contains(SopsBundle.message("diff.tool.name")))
            println("[accept] setting off: viewer not offered")

            settings.show_decrypted_diff = true
            if (target == "native") {
                settings.age_key_file = Path.of(requireNotNull(fixture().key_file)).resolveSibling("missing-keys.txt").toString()
            } else {
                settings.wsl_sops_path = "sops-missing"
            }
            processor.updateRequest(true)
            assertTrue(processor.activeViewer is SopsDecryptedDiffViewer)
            pump_until("failure notice") { notice_texts(processor).any { it.contains("Could not decrypt the left side") } }
            println("[accept] failure notice: ${notice_texts(processor).first { it.contains("Could not decrypt") }.take(200)}")
            assertTrue(editor_texts(processor).all { it == SopsBundle.message("diff.decrypt.failed.placeholder") })
            processor.switch_to(SimpleDiffTool.INSTANCE)
            pump_for(300)
            assertTrue("ciphertext viewer stays usable", editor_texts(processor).all { it.contains("ENC[") })
            processor.switch_to(sops_tool)

            val plain_request = SimpleDiffRequest(
                "plain",
                DiffContentFactory.getInstance().create(project, "a: 1\n"),
                DiffContentFactory.getInstance().create(project, "a: 2\n"),
                "left",
                "right",
            )
            assertFalse(sops_tool.canShow(processor.context, plain_request))
            log_has_no_native_call()
            println("[accept] decrypted diff ok")
        }

        fun testRemoveFixture() {
            remove_fixture()
        }
    }
}
