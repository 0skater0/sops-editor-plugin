package dev.ott.sops.editor

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.TextEditorWithPreview
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotifications
import dev.ott.sops.editor.notifications.SopsNotifier
import dev.ott.sops.editor.settings.SopsSettings

class SopsEditor(
    private val project: Project,
    val original_file: VirtualFile,
    decrypted_editor: TextEditor,
    encrypted_editor: TextEditor,
) : TextEditorWithPreview(
    decrypted_editor,
    encrypted_editor,
    SopsBundle.message("editor.name"),
    Layout.SHOW_EDITOR_AND_PREVIEW,
) {

    private val service = SopsService.get_instance(project)

    var original_decrypted_text: String = ""
        private set

    // @Volatile on the three flags below: writes happen in coroutine continuations
    // (BG thread, after `run_write_action_safely { … }` resumes), reads happen on EDT
    // (the DocumentListener and FileDocumentManager save listener). Without @Volatile a
    // stale read is theoretically possible on weakly-ordered architectures (Apple
    // Silicon, ARM servers).
    @Volatile
    var is_modified: Boolean = false
        private set

    @Volatile
    private var is_encrypting = false

    @Volatile
    private var decrypt_complete = false

    @Volatile
    private var save_pending = false

    init {
        SopsLog.info("SopsEditor init: ${original_file.name}")

        val encrypted_text = try {
            // IntelliJ's Document only accepts LF; SOPS writes the JSON envelope with the
            // platform's native line endings (CRLF on Windows), so normalise before setText.
            StringUtil.convertLineSeparators(String(original_file.contentsToByteArray(), Charsets.UTF_8))
        } catch (e: Exception) {
            // No throwable: IO exception messages carry the full file path, which would end up in idea.log.
            SopsLog.error("Failed to read file: ${original_file.name} (${e.javaClass.simpleName})")
            ""
        }
        SopsLog.info("Encrypted content loaded: ${encrypted_text.length} chars")

        val preview_editor = myPreview as? TextEditor
        if (preview_editor == null) {
            SopsLog.warn("Preview component is not a TextEditor — split view cannot show encrypted side")
        } else {
            ApplicationManager.getApplication().runWriteAction {
                preview_editor.editor.document.setText(encrypted_text)
            }
            // Editing a single ENC blob can only break it — user setting doesn't apply.
            val format = SopsFormat.from_file(original_file)
            val force_readonly = format == SopsFormat.BINARY ||
                !SopsSettings.get_instance().encrypted_side_editable
            if (force_readonly) {
                preview_editor.editor.document.setReadOnly(true)
            }
        }

        // Listen for changes on decrypted side.
        // The disposable parameter (`this`) auto-removes the listener when the SopsEditor
        // is disposed, so IntelliJ doesn't warn about a potentially leaking registration.
        myEditor.editor.document.addDocumentListener(
            object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) {
                    if (!is_encrypting && decrypt_complete) {
                        is_modified = myEditor.editor.document.text.trim() != original_decrypted_text.trim()
                    }
                }
            },
            this,
        )

        start_decrypt()
    }

    private fun start_decrypt() {
        SopsLog.info("Starting decrypt for ${original_file.name}")

        service.decrypt(original_file) { raw_decrypted_text ->
            val decrypted_text = StringUtil.convertLineSeparators(raw_decrypted_text)
            SopsLog.info("Decrypt callback: ${decrypted_text.length} chars")

            // On the EDT together with the text, like the end of a save, so the document listener
            // never sees the new text with the old state.
            run_write_action_safely {
                original_decrypted_text = decrypted_text
                myEditor.editor.document.setText(decrypted_text)
                decrypt_complete = true
                is_modified = false
            }
            SopsLog.info("Decrypted content set in editor")
        }
    }

    fun retry_decrypt() {
        SopsLog.info("Retry decrypt for ${original_file.name}")
        service.clear_error(original_file.path)
        // The error may come from another editor of the same file; decrypting again here would
        // replace the unsaved changes of this one with the content on disk.
        if (is_modified) {
            SopsLog.info("Retry decrypt skipped, the editor has unsaved changes")
            SopsNotifier.notify_info(project, SopsBundle.message("notification.retry.skipped"))
            EditorNotifications.getInstance(project).updateNotifications(original_file)
            return
        }
        decrypt_complete = false
        start_decrypt()
        EditorNotifications.getInstance(project).updateNotifications(original_file)
    }

    fun save_changes() {
        if (!is_modified) {
            SopsLog.debug("save_changes: not modified, skipping")
            return
        }
        if (is_encrypting) {
            // A second sops run could finish first and the older one would then write stale
            // ciphertext, so the newer text is saved once the running save is done.
            SopsLog.debug("save_changes: a save is running, saving again afterwards")
            save_pending = true
            return
        }

        val current_text = myEditor.editor.document.text
        is_encrypting = true
        SopsLog.info("Saving changes for ${original_file.name}")

        // Without the reset a failed or skipped save would block every later save and the
        // modified tracking of the document listener.
        service.encrypt_and_save(
            original_file,
            current_text,
            original_decrypted_text,
            on_not_saved = {
                is_encrypting = false
                run_pending_save()
            },
        ) { raw_encrypted ->
            val encrypted = StringUtil.convertLineSeparators(raw_encrypted)
            SopsLog.info("Encrypt callback: ${encrypted.length} chars")

            val preview_editor = myPreview as? TextEditor
            run_write_action_safely {
                try {
                    if (preview_editor != null) {
                        val preview_doc = preview_editor.editor.document
                        if (preview_doc.isWritable) {
                            preview_doc.setText(encrypted)
                        } else {
                            preview_doc.setReadOnly(false)
                            preview_doc.setText(encrypted)
                            preview_doc.setReadOnly(true)
                        }
                    }
                } finally {
                    // The listener ignores edits while is_encrypting is set. Clearing the flag and
                    // comparing against the saved text in the same EDT action leaves no gap in
                    // which a keystroke could be counted as saved.
                    original_decrypted_text = current_text
                    is_modified = myEditor.editor.document.text.trim() != current_text.trim()
                    is_encrypting = false
                }
            }
            SopsLog.info("Encrypted content updated in editor")
            run_pending_save()
        }
    }

    // Callers may be on a background thread, and save_changes() reads the document.
    private fun run_pending_save() {
        if (!save_pending) {
            return
        }
        save_pending = false
        ApplicationManager.getApplication().invokeLater({ save_changes() }, ModalityState.nonModal(), project.disposed)
    }

    fun get_decrypted_text(): String = myEditor.editor.document.text

    // Manual saves (the Encrypt with SOPS action) only make sense once the plaintext has loaded
    // and differs from what was decrypted, matching the guards of the save path.
    fun can_save_manually(): Boolean = decrypt_complete && is_modified && !is_encrypting

    // Return the decrypted document shown here, not the on-disk ciphertext: the daemon resolves
    // the file from getFile() but runs passes against this editor, and a mismatch makes it
    // restart endlessly (high CPU). Save and notifications use the original_file field directly.
    override fun getFile(): VirtualFile = myEditor.file ?: original_file

    override fun isModified(): Boolean = is_modified
}
