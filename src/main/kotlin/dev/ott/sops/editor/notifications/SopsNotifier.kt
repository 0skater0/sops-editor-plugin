package dev.ott.sops.editor.notifications

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import dev.ott.sops.editor.SopsBundle
import dev.ott.sops.editor.settings.SopsSettingsConfigurable

object SopsNotifier {

    fun notify_error(project: Project?, title: String, message: String) {
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup("sops.notification.important")
            .createNotification(title, message, NotificationType.ERROR)
            .addAction(open_settings_action())

        notification.notify(project)
    }

    fun notify_info(project: Project?, message: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("sops.notification.general")
            .createNotification(message, NotificationType.INFORMATION)
            .notify(project)
    }

    fun notify_decrypt_error(project: Project?, error: String) {
        notify_error(
            project,
            SopsBundle.message("notification.decrypt.error"),
            SopsBundle.message("notification.decrypt.failed", error),
        )
    }

    fun notify_encrypt_error(project: Project?, error: String) {
        notify_error(
            project,
            SopsBundle.message("notification.encrypt.error"),
            SopsBundle.message("notification.encrypt.failed", error),
        )
    }

    fun notify_encrypt_success(project: Project?) {
        notify_info(project, SopsBundle.message("notification.encrypt.success"))
    }

    private fun open_settings_action(): com.intellij.notification.NotificationAction {
        return com.intellij.notification.NotificationAction.createSimple(
            SopsBundle.message("notification.open.settings")
        ) {
            ShowSettingsUtil.getInstance()
                .showSettingsDialog(null, SopsSettingsConfigurable::class.java)
        }
    }
}
