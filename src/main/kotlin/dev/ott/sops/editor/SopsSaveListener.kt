package dev.ott.sops.editor

import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.ProjectManager
import dev.ott.sops.editor.settings.SopsSettings

class SopsSaveListener : FileDocumentManagerListener {

    override fun beforeAllDocumentsSaving() {
        if (!SopsSettings.get_instance().auto_encrypt_on_save) return

        for (project in ProjectManager.getInstance().openProjects) {
            val editors = FileEditorManager.getInstance(project).allEditors
            for (editor in editors) {
                if (editor is SopsEditor && editor.is_modified) {
                    SopsLog.info("Auto-encrypting on save: ${editor.file.name}")
                    editor.save_changes()
                }
            }
        }
    }
}
