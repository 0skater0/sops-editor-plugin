package dev.ott.sops.editor

import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.fileEditor.FileEditorManager

/**
 * Encrypts a plaintext file in place, or, inside the SOPS split editor, encrypts the current
 * plaintext of the left pane and writes it. The second case is the manual save when automatic
 * re-encryption on save is turned off.
 */
class SopsEncryptAction : AnAction() {

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return

        val sops_editor = find_sops_editor(event)
        if (sops_editor != null) {
            if (sops_editor.can_save_manually()) {
                sops_editor.save_changes()
            }
            return
        }

        val file = event.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        if (!is_plaintext_file_on_disk(file)) {
            return
        }

        SopsService.get_instance(project).encrypt_file(file) {
            // File is now encrypted, refresh to show in SOPS editor
            file.refresh(false, false)
        }
    }

    override fun update(event: AnActionEvent) {
        val sops_editor = find_sops_editor(event)
        if (sops_editor != null) {
            event.presentation.isVisible = true
            event.presentation.isEnabled = sops_editor.can_save_manually()
            return
        }
        val file = event.getData(CommonDataKeys.VIRTUAL_FILE)
        event.presentation.isEnabledAndVisible = file != null && is_plaintext_file_on_disk(file)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    // In-memory files such as the decrypted pane of the split editor have no file on disk that
    // sops could encrypt in place.
    private fun is_plaintext_file_on_disk(file: com.intellij.openapi.vfs.VirtualFile): Boolean {
        return !file.isDirectory && file.isInLocalFileSystem && !SopsDetector.is_sops_file(file)
    }

    /**
     * The split editor counts as active when it is the context's file editor, when the context
     * file is its decrypted pane, or, on its editor tab, the SOPS file itself.
     */
    private fun find_sops_editor(event: AnActionEvent): SopsEditor? {
        (event.getData(PlatformCoreDataKeys.FILE_EDITOR) as? SopsEditor)?.let { return it }
        val project = event.project ?: return null
        val file = event.getData(CommonDataKeys.VIRTUAL_FILE) ?: return null
        val on_editor_tab = event.place == ActionPlaces.EDITOR_TAB_POPUP
        return FileEditorManager.getInstance(project).allEditors
            .filterIsInstance<SopsEditor>()
            .firstOrNull { it.file == file || (on_editor_tab && it.original_file == file) }
    }
}
