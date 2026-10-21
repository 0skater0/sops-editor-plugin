package dev.ott.sops.editor

import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.FileEditorManagerTestCase
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.ui.HyperlinkLabel
import com.intellij.util.ui.UIUtil
import dev.ott.sops.editor.notifications.SopsEditorNotificationProvider
import dev.ott.sops.editor.settings.SopsSettings
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JComponent

/**
 * A failed save must never lead to the file being decrypted again over unsaved changes: the save
 * failure offers no decrypt retry, and the retry of a decrypt error leaves a modified editor alone.
 */
class SopsSaveFailureTest {

    @Test
    fun `a failed save keeps the unsaved changes`() {
        val case = Cases()
        case.name = "testFailedSaveKeepsChanges"
        case.runBare()
    }

    @Test
    fun `typing while a save runs keeps the editor modified`() {
        val case = Cases()
        case.name = "testTypingDuringSaveStaysModified"
        case.runBare()
    }

    @Test
    fun `a save requested during a running save writes the latest text last`() {
        val case = Cases()
        case.name = "testSecondSaveWaitsForTheFirst"
        case.runBare()
    }

    class Cases : FileEditorManagerTestCase() {
        private val settings get() = SopsSettings.get_instance()
        private lateinit var saved: SopsSettings.State
        private lateinit var work_dir: Path
        private val notifications = mutableListOf<Notification>()

        override fun setUp() {
            super.setUp()
            saved = settings.state.copy()
            work_dir = Files.createTempDirectory("sops-save-failure").toRealPath()
            VfsRootAccess.allowRootAccess(testRootDisposable, work_dir.toString())
            settings.custom_environment = ""
            settings.age_key_file = ""
            settings.auto_encrypt_on_save = true
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
                if (::saved.isInitialized) {
                    settings.loadState(saved)
                }
                SopsWrapper.clear_version_cache()
            } finally {
                try {
                    super.tearDown()
                } finally {
                    if (::work_dir.isInitialized) {
                        work_dir.toFile().deleteRecursively()
                    }
                }
            }
        }

        /**
         * Decrypting prints a fixed plaintext unless [fail_marker] exists, encrypting always fails
         * like a missing creation rule.
         */
        private fun fake_sops(fail_marker: Path): String {
            return if (SystemInfo.isWindows) {
                val script = listOf(
                    "@echo off",
                    "if \"%~1\"==\"--version\" (",
                    "  echo sops 3.12.0",
                    "  exit /b 0",
                    ")",
                    "if \"%~1\"==\"--decrypt\" goto decrypt",
                    "echo error loading config: no matching creation rules found 1>&2",
                    "exit /b 1",
                    ":decrypt",
                    "if exist \"$fail_marker\" (",
                    "  echo Failed to get the data key 1>&2",
                    "  exit /b 128",
                    ")",
                    "echo password: original",
                    "exit /b 0",
                ).joinToString("\r\n", postfix = "\r\n")
                work_dir.resolve("fake-sops.cmd").also { Files.writeString(it, script) }.toString()
            } else {
                val script = listOf(
                    "#!/bin/sh",
                    "case \"\$1\" in",
                    "  --version) echo 'sops 3.12.0'; exit 0 ;;",
                    "  --decrypt)",
                    "    if [ -e '$fail_marker' ]; then echo 'Failed to get the data key' >&2; exit 128; fi",
                    "    echo 'password: original'; exit 0 ;;",
                    "esac",
                    "echo 'error loading config: no matching creation rules found' >&2",
                    "exit 1",
                ).joinToString("\n", postfix = "\n")
                val path = work_dir.resolve("fake-sops.sh")
                Files.writeString(path, script)
                path.toFile().setExecutable(true)
                path.toString()
            }
        }

        // Decrypting prints a fixed plaintext, encrypting always succeeds with a fixed ciphertext.
        private fun saving_sops(): String {
            return if (SystemInfo.isWindows) {
                val script = listOf(
                    "@echo off",
                    "if \"%~1\"==\"--version\" (",
                    "  echo sops 3.12.0",
                    "  exit /b 0",
                    ")",
                    "if \"%~1\"==\"--decrypt\" (",
                    "  echo password: original",
                    "  exit /b 0",
                    ")",
                    "echo password: ENC[AES256_GCM,data:z,type:str]",
                    "echo sops:",
                    "echo     mac: ENC[AES256_GCM,data:y,type:str]",
                    "exit /b 0",
                ).joinToString("\r\n", postfix = "\r\n")
                work_dir.resolve("saving-sops.cmd").also { Files.writeString(it, script) }.toString()
            } else {
                val script = listOf(
                    "#!/bin/sh",
                    "case \"\$1\" in",
                    "  --version) echo 'sops 3.12.0'; exit 0 ;;",
                    "  --decrypt) echo 'password: original'; exit 0 ;;",
                    "esac",
                    "cat > /dev/null",
                    "printf 'password: ENC[AES256_GCM,data:z,type:str]\\nsops:\\n    mac: ENC[AES256_GCM,data:y,type:str]\\n'",
                    "exit 0",
                ).joinToString("\n", postfix = "\n")
                val path = work_dir.resolve("saving-sops.sh")
                Files.writeString(path, script)
                path.toFile().setExecutable(true)
                path.toString()
            }
        }

        // Encrypting echoes the plaintext as "ciphertext" and takes two seconds when it contains
        // "slow", so a later, faster run would overtake it.
        private fun echoing_sops(): String {
            return if (SystemInfo.isWindows) {
                val input = work_dir.resolve("echo-input-%RANDOM%.txt")
                val script = listOf(
                    "@echo off",
                    "if \"%~1\"==\"--version\" (",
                    "  echo sops 3.12.0",
                    "  exit /b 0",
                    ")",
                    "if \"%~1\"==\"--decrypt\" (",
                    "  echo password: original",
                    "  exit /b 0",
                    ")",
                    "set \"input=$input\"",
                    "findstr \"^\" > \"%input%\"",
                    "findstr /c:\"slow\" \"%input%\" >nul && ping -n 3 127.0.0.1 >nul",
                    "type \"%input%\"",
                    "del \"%input%\"",
                    "exit /b 0",
                ).joinToString("\r\n", postfix = "\r\n")
                work_dir.resolve("echoing-sops.cmd").also { Files.writeString(it, script) }.toString()
            } else {
                val script = listOf(
                    "#!/bin/sh",
                    "case \"\$1\" in",
                    "  --version) echo 'sops 3.12.0'; exit 0 ;;",
                    "  --decrypt) echo 'password: original'; exit 0 ;;",
                    "esac",
                    "input=\$(cat)",
                    "case \"\$input\" in *slow*) sleep 2 ;; esac",
                    "printf '%s\\n' \"\$input\"",
                    "exit 0",
                ).joinToString("\n", postfix = "\n")
                val path = work_dir.resolve("echoing-sops.sh")
                Files.writeString(path, script)
                path.toFile().setExecutable(true)
                path.toString()
            }
        }

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

        private fun pump_for(ms: Long) {
            val deadline = System.currentTimeMillis() + ms
            while (System.currentTimeMillis() < deadline) {
                PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
                Thread.sleep(20)
            }
        }

        private fun banner(editor: SopsEditor): JComponent? {
            val data = SopsEditorNotificationProvider().collectNotificationData(project, editor.original_file)
            return data?.apply(editor)
        }

        fun testFailedSaveKeepsChanges() {
            val fail_marker = work_dir.resolve("fail-decrypt")
            settings.sops_path = fake_sops(fail_marker)
            val path = work_dir.resolve("secrets.yaml")
            Files.writeString(path, "password: ENC[AES256_GCM,data:x,type:str]\nsops:\n    lastmodified: \"2026-10-21T12:00:00Z\"\n    mac: ENC[AES256_GCM,data:y,type:str]\n")
            val file = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByPath(path.toString().replace('\\', '/')))
            FileEditorManager.getInstance(project).openFile(file, true)
            val editor = requireNotNull(FileEditorManager.getInstance(project).getAllEditors(file).filterIsInstance<SopsEditor>().firstOrNull())
            val document = editor.textEditor.editor.document
            pump_until("decrypted text") { document.text.contains("password: original") }

            val edited = "password: changed-but-not-saved\n"
            WriteCommandAction.runWriteCommandAction(project) { document.setText(edited) }
            assertTrue(editor.is_modified)
            editor.save_changes()
            pump_until("encrypt failure notification") { notifications.any { it.content.contains("no matching creation rules") } }
            pump_for(300)
            val failure = notifications.first { it.content.contains("no matching creation rules") }
            val actions = failure.actions.map { it.templatePresentation.text }
            assertFalse("the save failure must not offer a decrypt retry: $actions", actions.contains(SopsBundle.message("notification.try.again")))
            assertNull("a save failure must not show the decrypt error banner", banner(editor))
            assertEquals(edited, document.text)
            assertTrue(editor.is_modified)
            assertTrue("saving must stay possible", editor.can_save_manually())

            // A decrypt error of the same file, for example from a second editor, shows the banner
            // on this editor too. Its retry must not replace the unsaved changes.
            Files.writeString(fail_marker, "")
            var decrypted_again = false
            SopsService.get_instance(project).decrypt(file) { decrypted_again = true }
            pump_until("decrypt error banner") { banner(editor) != null }
            assertFalse(decrypted_again)
            val retry = requireNotNull(banner(editor)).let { panel ->
                UIUtil.findComponentsOfType(panel, HyperlinkLabel::class.java).first { it.text == SopsBundle.message("notification.try.again") }
            }
            Files.delete(fail_marker)
            retry.doClick()
            pump_until("retry skipped notice") { notifications.any { it.content == SopsBundle.message("notification.retry.skipped") } }
            pump_for(1000)
            assertEquals(edited, document.text)
            assertTrue(editor.is_modified)
            assertNull(banner(editor))
            assertEquals("only the initial decrypt reached the editor", 1, SopsLog.read().lines().count { it.contains("Decrypt callback") })
            FileEditorManager.getInstance(project).closeFile(file)
        }

        fun testTypingDuringSaveStaysModified() {
            settings.sops_path = saving_sops()
            val path = work_dir.resolve("secrets.yaml")
            Files.writeString(path, "password: ENC[AES256_GCM,data:x,type:str]\nsops:\n    lastmodified: \"2026-10-21T12:00:00Z\"\n    mac: ENC[AES256_GCM,data:y,type:str]\n")
            val file = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByPath(path.toString().replace('\\', '/')))
            FileEditorManager.getInstance(project).openFile(file, true)
            val editor = requireNotNull(FileEditorManager.getInstance(project).getAllEditors(file).filterIsInstance<SopsEditor>().firstOrNull())
            val document = editor.textEditor.editor.document
            pump_until("decrypted text") { document.text.contains("password: original") }

            WriteCommandAction.runWriteCommandAction(project) { document.setText("password: saved\n") }
            editor.save_changes()
            // Stop pumping once the encrypt callback runs in the background, so the EDT step that
            // ends the save is still queued when the next keystroke arrives.
            val deadline = System.currentTimeMillis() + 30_000
            while (!SopsLog.read().contains("Encrypt callback")) {
                assertTrue("timeout waiting for the encrypt callback", System.currentTimeMillis() < deadline)
                PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
                Thread.sleep(20)
            }
            Thread.sleep(300)
            val typed = "password: typed-during-save\n"
            WriteCommandAction.runWriteCommandAction(project) { document.setText(typed) }
            pump_until("save finished") { SopsLog.read().contains("Encrypted content updated in editor") }

            assertEquals(typed, document.text)
            assertTrue("a keystroke during the save must not count as saved", editor.is_modified)
            assertTrue(editor.can_save_manually())
            FileEditorManager.getInstance(project).closeFile(file)
        }

        fun testSecondSaveWaitsForTheFirst() {
            settings.sops_path = echoing_sops()
            val path = work_dir.resolve("secrets.yaml")
            Files.writeString(path, "password: ENC[AES256_GCM,data:x,type:str]\nsops:\n    lastmodified: \"2026-10-21T12:00:00Z\"\n    mac: ENC[AES256_GCM,data:y,type:str]\n")
            val file = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByPath(path.toString().replace('\\', '/')))
            FileEditorManager.getInstance(project).openFile(file, true)
            val editor = requireNotNull(FileEditorManager.getInstance(project).getAllEditors(file).filterIsInstance<SopsEditor>().firstOrNull())
            val document = editor.textEditor.editor.document
            pump_until("decrypted text") { document.text.contains("password: original") }

            WriteCommandAction.runWriteCommandAction(project) { document.setText("password: slow-first\n") }
            editor.save_changes()
            WriteCommandAction.runWriteCommandAction(project) { document.setText("password: fast-second\n") }
            editor.save_changes()
            pump_until("both saves done") { SopsLog.read().lines().count { it.contains("Encrypted content updated in editor") } >= 2 }
            pump_for(1000)

            assertTrue("the newer text must be on disk: ${Files.readString(path)}", Files.readString(path).contains("fast-second"))
            assertFalse(editor.is_modified)
            assertEquals("one sops run at a time", 2, SopsLog.read().lines().count { it.contains("Encrypt callback") })
            FileEditorManager.getInstance(project).closeFile(file)
        }
    }
}
