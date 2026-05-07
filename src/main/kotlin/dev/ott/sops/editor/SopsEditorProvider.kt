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

    private companion object {
        // Bound on the file size considered for the split-view editor. Larger files would
        // load fully into editor memory both for plaintext and ciphertext sides; for SOPS
        // payloads this is more than enough headroom (typical files are well under 100 KB).
        const val MAX_SOPS_FILE_SIZE_BYTES = 5_000_000L
    }

    override fun accept(project: Project, file: VirtualFile): Boolean {
        if (file.isDirectory || !file.isValid) return false
        if (file.length > MAX_SOPS_FILE_SIZE_BYTES) return false
        val result = SopsDetector.is_sops_file(file)
        SopsLog.debug("SopsEditorProvider.accept(${file.name}): $result")
        return result
    }

    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        SopsLog.info("SopsEditorProvider.createEditor(${file.name})")

        val format = SopsFormat.from_file(file)
        val (decrypted_type, encrypted_type) = pick_editor_file_types(file, format)

        val decrypted_file = LightVirtualFile(file.name, decrypted_type, SopsBundle.message("editor.decrypting"))
        val encrypted_file = LightVirtualFile(file.name, encrypted_type, "")

        val decrypted_editor = TextEditorProvider.getInstance().createEditor(project, decrypted_file) as TextEditor
        val encrypted_editor = TextEditorProvider.getInstance().createEditor(project, encrypted_file) as TextEditor

        return SopsEditor(project, file, decrypted_editor, encrypted_editor)
    }

    /**
     * Decrypted side highlights by the inner extension (`app.conf.sops` → `.conf`)
     * because the plaintext payload is the user's actual config or document. Encrypted side
     * for binary mode is a JSON envelope, so PlainText avoids misleading highlighting; for
     * structured formats it keeps the same type as the decrypted side so both panels look
     * consistent.
     */
    private fun pick_editor_file_types(
        file: VirtualFile,
        format: SopsFormat,
    ): Pair<com.intellij.openapi.fileTypes.FileType, com.intellij.openapi.fileTypes.FileType> {
        val raw_type_for = { name: String ->
            FileTypeManager.getInstance().getFileTypeByFileName(name)
        }
        val type_for_format = {
            // Falls back to the format's canonical extension so a content-detected file with
            // an unfamiliar extension still gets the correct highlighting on the decrypted
            // side, even when IntelliJ doesn't know the file's actual extension.
            raw_type_for("placeholder${format.primary_extension}").let {
                if (it is UnknownFileType) PlainTextFileType.INSTANCE else it
            }
        }
        if (format == SopsFormat.BINARY) {
            // Decrypted side: highlight by the inner extension (`app.conf.sops` → `.conf`);
            // encrypted side stays PlainText since the JSON envelope is not the user's
            // content format.
            val inner_name = strip_known_extension(file.name, format)
            val decrypted_type = if (inner_name.isNotBlank() && inner_name != file.name) {
                raw_type_for(inner_name).let {
                    if (it is UnknownFileType) PlainTextFileType.INSTANCE else it
                }
            } else {
                PlainTextFileType.INSTANCE
            }
            return Pair(decrypted_type, PlainTextFileType.INSTANCE)
        }
        // Structured formats: try the file's actual extension first; if IntelliJ doesn't
        // recognise it, fall back to the format's canonical extension.
        val direct = raw_type_for(file.name)
        val t = if (direct is UnknownFileType) type_for_format() else direct
        return Pair(t, t)
    }

    private fun strip_known_extension(name: String, format: SopsFormat): String {
        val lower = name.lowercase()
        val ext = format.extensions.firstOrNull { lower.endsWith(it) } ?: return name
        return name.dropLast(ext.length)
    }

    override fun getEditorTypeId(): String = "sops-split-editor"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}
