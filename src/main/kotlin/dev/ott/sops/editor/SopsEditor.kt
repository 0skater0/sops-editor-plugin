package dev.ott.sops.editor

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.TextEditorWithPreview
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotifications
import dev.ott.sops.editor.settings.SopsSettings

class SopsEditor(
    private val project: Project,
    private val original_file: VirtualFile,
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

    init {
        SopsLog.info("SopsEditor init: ${original_file.name}")

        val encrypted_text = try {
            // IntelliJ's Document only accepts LF; SOPS writes the JSON envelope with the
            // platform's native line endings (CRLF on Windows), so normalise before setText.
            StringUtil.convertLineSeparators(String(original_file.contentsToByteArray(), Charsets.UTF_8))
        } catch (e: Exception) {
            SopsLog.error("Failed to read file: ${original_file.name}", e)
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
            original_decrypted_text = decrypted_text

            run_write_action_safely {
                myEditor.editor.document.setText(decrypted_text)
            }
            decrypt_complete = true
            is_modified = false
            SopsLog.info("Decrypted content set in editor")
        }
    }

    fun retry_decrypt() {
        SopsLog.info("Retry decrypt for ${original_file.path}")
        service.clear_error(original_file.path)
        decrypt_complete = false
        start_decrypt()
        EditorNotifications.getInstance(project).updateNotifications(original_file)
    }

    fun save_changes() {
        if (!is_modified) {
            SopsLog.debug("save_changes: not modified, skipping")
            return
        }

        val current_text = myEditor.editor.document.text
        is_encrypting = true
        SopsLog.info("Saving changes for ${original_file.name}")

        service.encrypt_and_save(original_file, current_text, original_decrypted_text) { raw_encrypted ->
            val encrypted = StringUtil.convertLineSeparators(raw_encrypted)
            SopsLog.info("Encrypt callback: ${encrypted.length} chars")
            original_decrypted_text = current_text
            is_modified = false

            val preview_editor = myPreview as? TextEditor
            if (preview_editor != null) {
                run_write_action_safely {
                    val preview_doc = preview_editor.editor.document
                    if (preview_doc.isWritable) {
                        preview_doc.setText(encrypted)
                    } else {
                        preview_doc.setReadOnly(false)
                        preview_doc.setText(encrypted)
                        preview_doc.setReadOnly(true)
                    }
                }
            }
            is_encrypting = false
            SopsLog.info("Encrypted content updated in editor")
        }
    }

    fun get_decrypted_text(): String = myEditor.editor.document.text

    // Return the decrypted document shown here, not the on-disk ciphertext: the daemon resolves
    // the file from getFile() but runs passes against this editor, and a mismatch makes it
    // restart endlessly (high CPU). Save and notifications use the original_file field directly.
    override fun getFile(): VirtualFile = myEditor.file ?: original_file

    override fun isModified(): Boolean = is_modified
}
