package dev.ott.sops.editor

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys

class SopsEncryptAction : AnAction() {

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val file = event.getData(CommonDataKeys.VIRTUAL_FILE) ?: return

        if (SopsDetector.is_sops_file(file)) {
            // Already encrypted — no-op
            return
        }

        SopsService.get_instance(project).encrypt_file(file) {
            // File is now encrypted, refresh to show in SOPS editor
            file.refresh(false, false)
        }
    }

    override fun update(event: AnActionEvent) {
        val file = event.getData(CommonDataKeys.VIRTUAL_FILE)
        event.presentation.isEnabledAndVisible = file != null &&
                !file.isDirectory &&
                !SopsDetector.is_sops_file(file)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
