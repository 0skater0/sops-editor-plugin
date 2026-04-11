package dev.ott.sops.editor.notifications

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import dev.ott.sops.editor.SopsBundle
import dev.ott.sops.editor.SopsEditor
import dev.ott.sops.editor.SopsService
import java.util.function.Function
import javax.swing.JComponent

class SopsEditorNotificationProvider : EditorNotificationProvider {

    override fun collectNotificationData(
        project: com.intellij.openapi.project.Project,
        file: VirtualFile,
    ): Function<in FileEditor, out JComponent?>? {
        return Function { editor ->
            if (editor !is SopsEditor) return@Function null

            val service = SopsService.get_instance(project)
            val error = service.get_error(file.path) ?: return@Function null

            EditorNotificationPanel(editor, EditorNotificationPanel.Status.Error).apply {
                text = error
                createActionLabel(SopsBundle.message("notification.try.again")) {
                    service.clear_error(file.path)
                    editor.retry_decrypt()
                }
                createActionLabel(SopsBundle.message("notification.open.settings")) {
                    com.intellij.openapi.options.ShowSettingsUtil.getInstance()
                        .showSettingsDialog(
                            project,
                            dev.ott.sops.editor.settings.SopsSettingsConfigurable::class.java
                        )
                }
            }
        }
    }
}
