package dev.ott.sops.editor.diff

import dev.ott.sops.editor.SopsDetector
import dev.ott.sops.editor.SopsFormat

/**
 * Decides per side of a two-sided diff what the decrypted diff viewer shows. Pure logic on the
 * side texts, so it is unit testable without the diff framework.
 */
internal enum class DiffSideKind {
    DECRYPT,
    KEEP,
    EMPTY,
}

internal data class DiffSidePlan(
    val kind: DiffSideKind,
    val format: SopsFormat? = null,
)

internal object SopsDiffSides {

    // Same bound as the split editor: larger contents are not SOPS payloads worth decrypting.
    const val MAX_CONTENT_LENGTH = 5_000_000

    /**
     * Returns null when the decrypted viewer does not apply: not exactly two sides, a side that
     * is not text (null), an oversized side, or no SOPS-encrypted side at all. Plaintext sides,
     * for example the revision before a file was first encrypted, are shown unchanged. A side may
     * also be passed as its head and tail window, see [SopsDetector.head_and_tail].
     */
    fun plan(texts: List<CharSequence?>): List<DiffSidePlan>? {
        if (texts.size != 2) {
            return null
        }
        val plans = texts.map { text ->
            when {
                text == null || text.length > MAX_CONTENT_LENGTH -> return null
                text.isBlank() -> DiffSidePlan(DiffSideKind.EMPTY)
                else -> SopsDetector.detect_sops_format(text.toString())
                    ?.let { DiffSidePlan(DiffSideKind.DECRYPT, it) }
                    ?: DiffSidePlan(DiffSideKind.KEEP)
            }
        }
        if (plans.none { it.kind == DiffSideKind.DECRYPT }) {
            return null
        }
        return plans
    }
}
