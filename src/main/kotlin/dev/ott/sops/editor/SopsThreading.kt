package dev.ott.sops.editor

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Schedules a write action on the EDT with an explicit non-modal modality and suspends
 * until it completes. Built only on stable IntelliJ Platform APIs so the plugin verifier
 * reports no experimental-API usages, in contrast to the suspend `writeAction { }` helper
 * that ships with the platform but is annotated `@ApiStatus.Experimental`.
 *
 * The non-modal modality matters because the platform's reload-from-disk listener fires
 * inside `VirtualFile.setBinaryContent` and tries to acquire its own write action; that
 * inner write action fails with `Write-unsafe context!` if the outer dispatcher's modality
 * is `any`, which is what `Dispatchers.Main` resolves to in a coroutine context.
 */
internal suspend fun run_write_action_safely(action: () -> Unit) {
    suspendCancellableCoroutine<Unit> { cont ->
        ApplicationManager.getApplication().invokeLater(
            Runnable {
                try {
                    ApplicationManager.getApplication().runWriteAction { action() }
                    cont.resume(Unit)
                } catch (t: Throwable) {
                    cont.resumeWithException(t)
                }
            },
            ModalityState.nonModal(),
        )
    }
}
