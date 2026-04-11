package dev.ott.sops.editor

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.fileTypes.UnknownFileType
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile

class SopsEditorProvider : FileEditorProvider, DumbAware {

    override fun accept(project: Project, file: VirtualFile): Boolean {
        if (file.isDirectory || !file.isValid) return false
        if (file.length > 5_000_000) return false
        val result = SopsDetector.is_sops_file(file)
        SopsLog.debug("SopsEditorProvider.accept(${file.name}): $result")
        return result
    }

    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        SopsLog.info("SopsEditorProvider.createEditor(${file.name})")

        val file_type = FileTypeManager.getInstance().getFileTypeByFileName(file.name).let {
            if (it is UnknownFileType) PlainTextFileType.INSTANCE else it
        }

        // LightVirtualFile for decrypted side — gets proper syntax highlighting from file type
        val decrypted_file = LightVirtualFile(file.name, file_type, SopsBundle.message("editor.decrypting"))

        // LightVirtualFile for encrypted side — same file type for consistency
        val encrypted_file = LightVirtualFile(file.name, file_type, "")

        val decrypted_editor = TextEditorProvider.getInstance().createEditor(project, decrypted_file) as TextEditor
        val encrypted_editor = TextEditorProvider.getInstance().createEditor(project, encrypted_file) as TextEditor

        return SopsEditor(project, file, decrypted_editor, encrypted_editor)
    }

    override fun getEditorTypeId(): String = "sops-split-editor"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}
