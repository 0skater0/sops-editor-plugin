package dev.ott.sops.editor

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.progress.ProgressIndicator
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.cancellation.CancellationException
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

/**
 * Runs [action] from a blocking progress task and cancels it as soon as [indicator] is canceled,
 * which stops a running sops process. Same idea as the platform's runBlockingCancellable, which is
 * still marked experimental in the 2024.2 to 2025.1 builds this plugin supports.
 */
internal fun <T> run_blocking_cancellable(indicator: ProgressIndicator, action: suspend () -> T): T {
    return runBlocking {
        val work = async { action() }
        val watcher = launch {
            while (!indicator.isCanceled) {
                delay(CANCEL_POLL_INTERVAL_MS)
            }
            work.cancel()
        }
        try {
            work.await()
        } catch (e: CancellationException) {
            // Progress tasks expect a canceled run to end with ProcessCanceledException.
            indicator.checkCanceled()
            throw e
        } finally {
            watcher.cancel()
        }
    }
}

private const val CANCEL_POLL_INTERVAL_MS = 50L
