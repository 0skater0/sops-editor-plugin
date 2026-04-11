package dev.ott.sops.editor

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComboBox
import javax.swing.JComponent

/**
 * Result of validating a user-entered filename for a new SOPS file. Kept separate from the
 * Swing dialog so it can be unit tested without spinning up the IntelliJ platform.
 */
enum class FilenameValidation {
    OK,
    EMPTY,
    INVALID_CHARACTERS,
    TOO_LONG,
}

fun validate_new_filename(raw: String): FilenameValidation {
    val name = raw.trim()
    if (name.isBlank()) return FilenameValidation.EMPTY
    if (name.length > 255) return FilenameValidation.TOO_LONG
    // Reject anything that could escape the target directory.
    if (name.contains("..") || name.contains('/') || name.contains('\\') || name.contains(java.io.File.separatorChar)) {
        return FilenameValidation.INVALID_CHARACTERS
    }
    // Whitelist: letters, digits, dot, underscore, dash. No spaces, drive letters, control chars.
    if (!name.matches(Regex("^[A-Za-z0-9._-]+$"))) {
        return FilenameValidation.INVALID_CHARACTERS
    }
    return FilenameValidation.OK
}

class SopsNewFileAction : AnAction() {

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val directory = event.getData(CommonDataKeys.VIRTUAL_FILE)?.let {
            if (it.isDirectory) it else it.parent
        } ?: return

        val dialog = NewSopsFileDialog()
        if (!dialog.showAndGet()) return

        val file_name = dialog.get_file_name()
        val format = dialog.get_format()
        val initial_content = format.template

        // Write the plaintext file + trigger encryption. The service internally launches a
        // background coroutine, so we don't need to block the EDT here.
        com.intellij.openapi.application.ApplicationManager.getApplication().runWriteAction {
            val file = directory.createChildData(this, file_name)
            file.setBinaryContent(initial_content.toByteArray(Charsets.UTF_8))

            SopsService.get_instance(project).encrypt_file(file) {
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                    FileEditorManager.getInstance(project).openFile(file, true)
                }
            }
        }
    }

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible = event.project != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

private class NewSopsFileDialog : DialogWrapper(true) {

    private val name_field = JBTextField(".env")
    private val format_combo = JComboBox(SopsFormat.entries.map { it.display_name }.toTypedArray())

    init {
        title = SopsBundle.message("action.new.file.dialog.title")
        init()

        format_combo.addActionListener {
            val selected = SopsFormat.entries[format_combo.selectedIndex]
            val current = name_field.text
            if (current.isBlank() || SopsFormat.entries.any { fmt ->
                    fmt.extensions.any { current.endsWith(it) || current == it.removePrefix(".") }
                }) {
                name_field.text = selected.primary_extension.removePrefix(".")
            }
        }
    }

    override fun createCenterPanel(): JComponent {
        return panel {
            row(SopsBundle.message("action.new.file.name")) {
                cell(name_field).focused()
            }
            row(SopsBundle.message("action.new.file.format")) {
                cell(format_combo)
            }
        }
    }

    override fun doValidate(): ValidationInfo? {
        return when (validate_new_filename(name_field.text)) {
            FilenameValidation.OK -> null
            FilenameValidation.EMPTY -> ValidationInfo(SopsBundle.message("action.new.file.error.empty"), name_field)
            FilenameValidation.INVALID_CHARACTERS -> ValidationInfo(SopsBundle.message("action.new.file.error.invalid.chars"), name_field)
            FilenameValidation.TOO_LONG -> ValidationInfo(SopsBundle.message("action.new.file.error.too.long"), name_field)
        }
    }

    fun get_file_name(): String = name_field.text.trim()

    fun get_format(): SopsFormat = SopsFormat.entries[format_combo.selectedIndex]
}
