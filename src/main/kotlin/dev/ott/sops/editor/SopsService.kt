package dev.ott.sops.editor

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import dev.ott.sops.editor.notifications.SopsNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
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
                SopsLog.warn("SOPS decrypt failed for ${file.name}: ${log_error(result)}")
                errors_by_path[file.path] = error
                SopsNotifier.notify_decrypt_error(project, error)
                refresh_notifications(file)
            }
        }
    }

    fun encrypt_and_save(
        file: VirtualFile,
        decrypted_text: String,
        original_decrypted_text: String,
        on_not_saved: () -> Unit = {},
        on_success: suspend (String) -> Unit,
    ) {
        if (decrypted_text.trim() == original_decrypted_text.trim()) {
            SopsLog.info("Content unchanged, skipping encryption for ${file.name}")
            on_not_saved()
            return
        }

        val format = SopsFormat.from_file(file)
        scope.launch {
            val result = try {
                SopsWrapper.encrypt_text(decrypted_text, format, file.path)
            } catch (e: CancellationException) {
                on_not_saved()
                throw e
            }
            if (result.success) {
                val encrypted_content = result.stdout
                try {
                    // setBinaryContent triggers the platform's reload-from-disk listener which
                    // needs a non-modal write context, so the helper schedules through
                    // invokeLater(nonModal()) instead of `Dispatchers.Main + runWriteAction`.
                    run_write_action_safely {
                        file.setBinaryContent(encrypted_content.toByteArray(Charsets.UTF_8))
                    }
                } catch (e: CancellationException) {
                    on_not_saved()
                    throw e
                } catch (e: Exception) {
                    // A read-only, deleted or locked file. Only the type goes to the log, the
                    // message of a file system error carries the path.
                    SopsLog.warn("Failed to write the encrypted file: ${e.javaClass.simpleName}")
                    val error = SopsLog.sanitize_for_log(e.message ?: e.javaClass.simpleName).trimEnd('.')
                    on_not_saved()
                    SopsNotifier.notify_error(
                        project,
                        SopsBundle.message("notification.encrypt.error"),
                        SopsBundle.message("notification.write.failed", error),
                    )
                    return@launch
                }
                on_success(encrypted_content)
                clear_error(file.path)
                SopsLog.info("File re-encrypted: ${file.name}")
            } else {
                val error = sanitize_error(result.stderr, result.exit_code)
                SopsLog.warn("SOPS encrypt failed for ${file.name}: ${log_error(result)}")
                // Notification only: the editor banner belongs to decrypt errors, and its retry
                // would decrypt the file again and replace the unsaved changes.
                on_not_saved()
                SopsNotifier.notify_encrypt_error(project, error)
            }
        }
    }

    fun encrypt_file(file: VirtualFile, on_success: suspend () -> Unit) {
        scope.launch {
            val result = SopsWrapper.encrypt_file_in_place(file)
            if (result.success) {
                run_write_action_safely {
                    file.refresh(false, false)
                }
                on_success()
                SopsNotifier.notify_encrypt_success(project)
            } else {
                val error = sanitize_error(result.stderr, result.exit_code)
                SopsNotifier.notify_encrypt_error(project, error)
            }
        }
    }

    /**
     * Encrypts the format's starter template first and only then creates the file with the
     * ciphertext, so a failed encryption never leaves a plaintext file behind.
     */
    fun create_encrypted_file(
        directory: VirtualFile,
        file_name: String,
        format: SopsFormat,
        on_created: suspend (VirtualFile) -> Unit,
    ) {
        if (directory.findChild(file_name) != null) {
            notify_new_file_error(SopsBundle.message("notification.new.file.exists", file_name))
            return
        }
        scope.launch {
            val result = SopsWrapper.encrypt_text(format.template, format, "${directory.path}/$file_name")
            if (!result.success) {
                val error = sanitize_error(result.stderr, result.exit_code)
                notify_new_file_error(SopsBundle.message("notification.new.file.not.created", error))
                return@launch
            }
            var created: VirtualFile? = null
            try {
                run_write_action_safely {
                    val file = directory.createChildData(this@SopsService, file_name)
                    created = file
                    file.setBinaryContent(result.stdout.toByteArray(Charsets.UTF_8))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Only the type: the exception message of a file system error carries the path.
                SopsLog.warn("Failed to create the new SOPS file: ${e.javaClass.simpleName}")
                // The message says the file was not created, so an empty file must not stay.
                created?.let { delete_quietly(it) }
                notify_new_file_error(
                    SopsBundle.message("notification.new.file.write.failed", SopsLog.sanitize_for_log(e.message ?: e.javaClass.simpleName)),
                )
                return@launch
            }
            created?.let { on_created(it) }
        }
    }

    private suspend fun delete_quietly(file: VirtualFile) {
        try {
            run_write_action_safely {
                file.delete(this@SopsService)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SopsLog.warn("Failed to remove the incomplete new SOPS file: ${e.javaClass.simpleName}")
        }
    }

    private fun notify_new_file_error(message: String) {
        SopsNotifier.notify_error(project, SopsBundle.message("notification.encrypt.error"), message)
    }

    fun decrypt_in_place(file: VirtualFile, on_success: suspend () -> Unit) {
        scope.launch {
            val result = SopsWrapper.decrypt_file_in_place(file)
            if (result.success) {
                run_write_action_safely {
                    file.refresh(false, false)
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

    // The notification may name the sops path so the user can fix it, the log must not.
    private fun log_error(result: SopsResult): String = sanitize_error(result.stderr_for_log, result.exit_code)

    companion object {
        private const val MAX_CACHED_ERRORS = 100

        fun get_instance(project: Project): SopsService {
            return project.getService(SopsService::class.java)
        }
    }
}
