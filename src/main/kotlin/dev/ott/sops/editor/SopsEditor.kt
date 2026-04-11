package dev.ott.sops.editor

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.TextEditorWithPreview
import com.intellij.openapi.project.Project
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
    var is_modified: Boolean = false
        private set

    private var is_encrypting = false
    private var decrypt_complete = false

    init {
        SopsLog.info("SopsEditor init: ${original_file.name}")

        val encrypted_text = try {
            String(original_file.contentsToByteArray(), Charsets.UTF_8)
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
            if (!SopsSettings.get_instance().encrypted_side_editable) {
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

        service.decrypt(original_file) { decrypted_text ->
            SopsLog.info("Decrypt callback: ${decrypted_text.length} chars")
            original_decrypted_text = decrypted_text

            ApplicationManager.getApplication().invokeLater {
                ApplicationManager.getApplication().runWriteAction {
                    myEditor.editor.document.setText(decrypted_text)
                }
                decrypt_complete = true
                is_modified = false
                SopsLog.info("Decrypted content set in editor")
            }
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

        service.encrypt_and_save(original_file, current_text, original_decrypted_text) { encrypted ->
            SopsLog.info("Encrypt callback: ${encrypted.length} chars")
            original_decrypted_text = current_text
            is_modified = false

            ApplicationManager.getApplication().invokeLater {
                val preview_editor = myPreview as? TextEditor ?: return@invokeLater
                ApplicationManager.getApplication().runWriteAction {
                    val preview_doc = preview_editor.editor.document
                    if (preview_doc.isWritable) {
                        preview_doc.setText(encrypted)
                    } else {
                        preview_doc.setReadOnly(false)
                        preview_doc.setText(encrypted)
                        preview_doc.setReadOnly(true)
                    }
                }
                is_encrypting = false
                SopsLog.info("Encrypted content updated in editor")
            }
        }
    }

    fun get_decrypted_text(): String = myEditor.editor.document.text

    override fun getFile(): VirtualFile = original_file

    override fun isModified(): Boolean = is_modified
}
