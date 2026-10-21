package dev.ott.sops.editor.diff

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffContext
import com.intellij.diff.EditorDiffViewer
import com.intellij.diff.FrameDiffTool
import com.intellij.diff.contents.DiffContent
import com.intellij.diff.contents.DocumentContent
import com.intellij.diff.contents.EmptyContent
import com.intellij.diff.requests.ContentDiffRequest
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.tools.simple.SimpleDiffTool
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.EditorNotificationPanel
import dev.ott.sops.editor.SopsBundle
import dev.ott.sops.editor.SopsDetector
import dev.ott.sops.editor.SopsLog
import dev.ott.sops.editor.SopsResult
import dev.ott.sops.editor.SopsWrapper
import dev.ott.sops.editor.run_blocking_cancellable
import dev.ott.sops.editor.settings.SopsSettings
import java.awt.BorderLayout
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Diff viewer "SOPS: decrypted" for diffs whose sides are SOPS-encrypted (VCS history, local
 * changes, compare with revision). It sits next to the built-in viewers in the viewer switcher,
 * so the ciphertext stays one click away. Offered only while the setting is on.
 */
class SopsDecryptedDiffTool : FrameDiffTool {

    override fun getName(): String = SopsBundle.message("diff.tool.name")

    // From 2026.2 on, FrameDiffTool has a default getIcon() that the viewer switcher shows, and
    // without this method the tool would get the side-by-side icon. Older builds lack the
    // interface method, so this cannot be marked override; the JVM still dispatches to it by
    // name and signature where it exists.
    fun getIcon(): Icon = AllIcons.Ide.Readwrite

    override fun canShow(context: DiffContext, request: DiffRequest): Boolean {
        if (!SopsSettings.get_instance().show_decrypted_diff) {
            return false
        }
        return plan_for(request) != null
    }

    override fun createComponent(context: DiffContext, request: DiffRequest): FrameDiffTool.DiffViewer {
        val content_request = request as ContentDiffRequest
        val plan = requireNotNull(plan_for(request)) { "canShow() was false for this request" }
        return SopsDecryptedDiffViewer(context, content_request, plan)
    }

    // The plan of a request, valid while the documents keep these modification stamps.
    private class CachedPlan(val stamps: List<Long>, val plan: List<DiffSidePlan>?)

    internal companion object {
        // The diff framework asks canShow() several times per request and then createComponent(),
        // all on the EDT; the cache keeps that to a single detection run.
        private val PLAN_KEY = Key.create<CachedPlan>("dev.ott.sops.editor.diff.plan")

        fun plan_for(request: DiffRequest): List<DiffSidePlan>? {
            val content_request = request as? ContentDiffRequest ?: return null
            val contents = content_request.contents
            val stamps = contents.map { (it as? DocumentContent)?.document?.modificationStamp ?: -1L }
            request.getUserData(PLAN_KEY)?.takeIf { it.stamps == stamps }?.let { return it.plan }
            val plan = SopsDiffSides.plan(contents.map(::detection_text_of))
            request.putUserData(PLAN_KEY, CachedPlan(stamps, plan))
            return plan
        }

        // Only the head and tail windows are copied: the SOPS markers sit at the start or the
        // end, and the full text is only needed once the viewer is actually shown.
        private fun detection_text_of(content: DiffContent): CharSequence? {
            return when (content) {
                is EmptyContent -> ""
                is DocumentContent -> {
                    val document = content.document
                    if (document.textLength > SopsDiffSides.MAX_CONTENT_LENGTH) {
                        null
                    } else {
                        SopsDetector.head_and_tail(document.immutableCharSequence)
                    }
                }
                else -> null
            }
        }

        fun text_of(content: DiffContent): String? {
            return when (content) {
                is EmptyContent -> ""
                is DocumentContent -> {
                    val document = content.document
                    if (document.textLength > SopsDiffSides.MAX_CONTENT_LENGTH) {
                        null
                    } else {
                        document.immutableCharSequence.toString()
                    }
                }
                else -> null
            }
        }
    }
}

/**
 * Wraps the platform side-by-side viewer around read-only documents that receive the decrypted
 * text once a background task has run sops. The plaintext lives only in these in-memory
 * documents and is gone with the viewer; nothing is written to disk or cached.
 */
internal class SopsDecryptedDiffViewer(
    private val context: DiffContext,
    private val request: ContentDiffRequest,
    private val plan: List<DiffSidePlan>,
) : EditorDiffViewer {

    private val source_texts: List<String> = request.contents.map { SopsDecryptedDiffTool.text_of(it).orEmpty() }

    private val documents: List<Document> = plan.mapIndexed { index, side ->
        val initial = when (side.kind) {
            DiffSideKind.DECRYPT -> SopsBundle.message("diff.decrypting")
            DiffSideKind.KEEP -> StringUtil.convertLineSeparators(source_texts[index])
            DiffSideKind.EMPTY -> ""
        }
        EditorFactory.getInstance().createDocument(initial).also { it.setReadOnly(true) }
    }

    private val inner: FrameDiffTool.DiffViewer
    private val notice_holder = JPanel(BorderLayout())
    private val panel = JPanel(BorderLayout())

    @Volatile
    private var disposed = false

    @Volatile
    private var progress: ProgressIndicator? = null

    init {
        val factory = DiffContentFactory.getInstance()
        val contents = documents.mapIndexed { index, document ->
            factory.create(context.project, document, request.contents[index].contentType)
        }
        val titles = request.contentTitles.mapIndexed { index, title ->
            if (plan[index].kind != DiffSideKind.DECRYPT) {
                title
            } else if (title == null) {
                SopsBundle.message("diff.title.decrypted.untitled")
            } else {
                SopsBundle.message("diff.title.decrypted", title)
            }
        }
        val decrypted_request = SimpleDiffRequest(request.title, contents, titles)
        decrypted_request.putUserData(DiffUserDataKeys.FORCE_READ_ONLY, true)
        inner = SimpleDiffTool.INSTANCE.createComponent(context, decrypted_request)
        // Older builds show tool names only in the viewer switcher, so the banner carries the icon too.
        show_notice(EditorNotificationPanel.Status.Info, SopsBundle.message("diff.notice"), AllIcons.Ide.Readwrite)
        panel.add(notice_holder, BorderLayout.NORTH)
        panel.add(inner.component, BorderLayout.CENTER)
    }

    override fun getComponent(): JComponent = panel

    override fun getPreferredFocusedComponent(): JComponent? = inner.preferredFocusedComponent

    // Lets diff features that work on the embedded editors (search, scrolling) reach them.
    override fun getEditors(): List<Editor> = (inner as? EditorDiffViewer)?.editors.orEmpty()

    override fun init(): FrameDiffTool.ToolbarComponents {
        val toolbar = inner.init()
        start_decrypt()
        return toolbar
    }

    override fun dispose() {
        disposed = true
        progress?.cancel()
        Disposer.dispose(inner)
    }

    private fun start_decrypt() {
        val project = context.project
        val context_path = project?.basePath
        val file_paths = request.contents.map { content ->
            (content as? DocumentContent)?.highlightFile?.takeIf { it.isInLocalFileSystem }?.path
        }
        val fallback_path = request.filesToRefresh.firstOrNull { it.isInLocalFileSystem }?.path
        // The diff window may be modal (for example a modal commit dialog), so the results are
        // delivered in the modality the viewer was created in.
        val modality = ModalityState.current()
        val sides = plan.indices.filter { plan[it].kind == DiffSideKind.DECRYPT }
        SopsLog.info("Decrypted diff: decrypting ${sides.size} side(s)")

        object : Task.Backgroundable(project, SopsBundle.message("diff.progress.title"), true) {
            override fun run(indicator: ProgressIndicator) {
                progress = indicator
                if (disposed) {
                    // dispose() may have run before the indicator was known here.
                    indicator.cancel()
                }
                val results = mutableMapOf<Int, SopsResult>()
                try {
                    for (index in sides) {
                        if (disposed || indicator.isCanceled) {
                            break
                        }
                        val format = requireNotNull(plan[index].format)
                        // Cancelling the indicator cancels this coroutine, which stops sops.
                        results[index] = run_blocking_cancellable(indicator) {
                            SopsWrapper.decrypt_text(
                                source_texts[index],
                                format,
                                file_paths[index] ?: fallback_path,
                                context_path,
                                timeout_ms = DECRYPT_TIMEOUT_MS,
                            )
                        }
                    }
                } finally {
                    val canceled = indicator.isCanceled
                    ApplicationManager.getApplication().invokeLater({ apply_results(sides, results, canceled) }, modality)
                }
            }
        }.queue()
    }

    private fun apply_results(sides: List<Int>, results: Map<Int, SopsResult>, canceled: Boolean) {
        if (disposed) {
            return
        }
        var first_error: String? = null
        ApplicationManager.getApplication().runWriteAction {
            for (index in sides) {
                val result = results[index]
                val text = if (result != null && result.success) {
                    StringUtil.convertLineSeparators(result.stdout)
                } else {
                    if (first_error == null) {
                        first_error = describe_failure(index, result, canceled)
                    }
                    SopsBundle.message("diff.decrypt.failed.placeholder")
                }
                val document = documents[index]
                document.setReadOnly(false)
                document.setText(text)
                document.setReadOnly(true)
            }
        }
        val error = first_error
        if (error != null) {
            SopsLog.warn("Decrypted diff: at least one side could not be decrypted")
            show_notice(EditorNotificationPanel.Status.Error, error)
        } else {
            SopsLog.info("Decrypted diff: done")
        }
    }

    private fun describe_failure(index: Int, result: SopsResult?, canceled: Boolean): String {
        if (result == null && canceled) {
            return SopsBundle.message("diff.decrypt.canceled")
        }
        val side = SopsBundle.message(if (index == 0) "diff.side.left" else "diff.side.right")
        val stderr = result?.stderr.orEmpty()
        val reason = SopsLog.sanitize_for_log(stderr).lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            ?.take(MAX_REASON_LENGTH)
            ?: "exit code ${result?.exit_code}"
        return SopsBundle.message("diff.decrypt.failed", side, reason.trimEnd('.'))
    }

    private fun show_notice(status: EditorNotificationPanel.Status, text: String, icon: Icon? = null) {
        val notice = EditorNotificationPanel(status).also { it.text = text }
        if (icon != null) {
            notice.icon(icon)
        }
        notice_holder.removeAll()
        notice_holder.add(notice, BorderLayout.CENTER)
        notice_holder.revalidate()
        notice_holder.repaint()
    }

    private companion object {
        const val MAX_REASON_LENGTH = 300

        // Shorter than the general sops timeout because nothing is written here and a hanging
        // diff keeps a background task busy, yet long enough for a KMS call or a pinentry prompt.
        const val DECRYPT_TIMEOUT_MS = 60_000
    }
}
