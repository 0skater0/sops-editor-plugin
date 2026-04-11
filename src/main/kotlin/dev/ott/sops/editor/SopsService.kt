package dev.ott.sops.editor

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import dev.ott.sops.editor.notifications.SopsNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Collections

@Service(Service.Level.PROJECT)
class SopsService(
    private val project: Project,
    private val scope: CoroutineScope,
) {
    // Access-order LRU map, bounded at MAX_CACHED_ERRORS entries. Bounded to keep long-running
    // IDE sessions from accumulating error entries indefinitely.
    private val errors_by_path: MutableMap<String, String> = Collections.synchronizedMap(
        object : LinkedHashMap<String, String>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
                size > MAX_CACHED_ERRORS
        }
    )

    fun decrypt(file: VirtualFile, on_success: suspend (String) -> Unit) {
        scope.launch {
            val result = SopsWrapper.decrypt_file(file)
            if (result.success) {
                on_success(result.stdout)
            } else {
                val error = sanitize_error(result.stderr, result.exit_code)
                SopsLog.warn("SOPS decrypt failed for ${file.name}: $error")
                errors_by_path[file.path] = error
                SopsNotifier.notify_decrypt_error(project, error)
                refresh_notifications(file)
            }
        }
    }

    fun decrypt_text(text: String, format: SopsFormat, on_success: suspend (String) -> Unit) {
        scope.launch {
            val result = SopsWrapper.decrypt_text(text, format)
            if (result.success) {
                on_success(result.stdout)
            } else {
                val error = sanitize_error(result.stderr, result.exit_code)
                SopsLog.warn("SOPS text decrypt failed: $error")
                SopsNotifier.notify_decrypt_error(project, error)
            }
        }
    }

    fun encrypt_and_save(
        file: VirtualFile,
        decrypted_text: String,
        original_decrypted_text: String,
        on_success: suspend (String) -> Unit,
    ) {
        if (decrypted_text.trim() == original_decrypted_text.trim()) {
            SopsLog.info("Content unchanged, skipping encryption for ${file.name}")
            return
        }

        val format = SopsFormat.from_file(file)
        scope.launch {
            val result = SopsWrapper.encrypt_text(decrypted_text, format, file.parent?.path)
            if (result.success) {
                val encrypted_content = result.stdout
                withContext(Dispatchers.Main) {
                    ApplicationManager.getApplication().runWriteAction {
                        file.setBinaryContent(encrypted_content.toByteArray(Charsets.UTF_8))
                    }
                }
                on_success(encrypted_content)
                clear_error(file.path)
                SopsLog.info("File re-encrypted: ${file.name}")
            } else {
                val error = sanitize_error(result.stderr, result.exit_code)
                SopsLog.warn("SOPS encrypt failed for ${file.name}: $error")
                errors_by_path[file.path] = error
                SopsNotifier.notify_encrypt_error(project, error)
                refresh_notifications(file)
            }
        }
    }

    fun encrypt_file(file: VirtualFile, on_success: suspend () -> Unit) {
        scope.launch {
            val result = SopsWrapper.encrypt_file_in_place(file)
            if (result.success) {
                withContext(Dispatchers.Main) {
                    ApplicationManager.getApplication().runWriteAction {
                        file.refresh(false, false)
                    }
                }
                on_success()
                SopsNotifier.notify_encrypt_success(project)
            } else {
                val error = sanitize_error(result.stderr, result.exit_code)
                SopsNotifier.notify_encrypt_error(project, error)
            }
        }
    }

    fun decrypt_in_place(file: VirtualFile, on_success: suspend () -> Unit) {
        scope.launch {
            val result = SopsWrapper.decrypt_file_in_place(file)
            if (result.success) {
                withContext(Dispatchers.Main) {
                    ApplicationManager.getApplication().runWriteAction {
                        file.refresh(false, false)
                    }
                }
                on_success()
            } else {
                val error = sanitize_error(result.stderr, result.exit_code)
                SopsNotifier.notify_decrypt_error(project, error)
            }
        }
    }

    fun get_error(path: String): String? = errors_by_path[path]

    fun clear_error(path: String) {
        errors_by_path.remove(path)
    }

    private fun refresh_notifications(file: VirtualFile) {
        com.intellij.ui.EditorNotifications.getInstance(project).updateNotifications(file)
    }

    /**
     * Produces a user-facing error string from a SOPS result. Stderr is run through the
     * central sanitizer to strip any leaked secret material before it reaches notifications
     * or the error cache.
     */
    private fun sanitize_error(stderr: String, exit_code: Int): String {
        val base = stderr.ifBlank { "Unknown error (exit code $exit_code)" }
        return SopsLog.sanitize_for_log(base)
    }

    companion object {
        private const val MAX_CACHED_ERRORS = 100

        fun get_instance(project: Project): SopsService {
            return project.getService(SopsService::class.java)
        }
    }
}
