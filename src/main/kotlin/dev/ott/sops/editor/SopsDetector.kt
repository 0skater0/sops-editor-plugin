package dev.ott.sops.editor

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VirtualFile

/**
 * Detects whether a file is SOPS-encrypted by looking for the metadata markers that SOPS
 * writes into every format. Only the first MAX_DETECTION_BYTES bytes are inspected so that
 * large YAML/JSON dumps don't cause unbounded reads. The structured-format heuristic requires
 * *both* a `sops:` / `[sops]` container and one of the mac / lastmodified fields, which
 * avoids false positives from user content that happens to mention one of the keywords.
 */
object SopsDetector {

    private const val MAX_DETECTION_BYTES = 16 * 1024  // 16 KB is plenty for SOPS metadata

    private val dotenv_keywords = listOf("sops_version=", "sops_lastmodified=", "sops_mac=")

    fun is_sops_file(file: VirtualFile): Boolean {
        return try {
            // Using Application.runReadAction directly rather than the Kotlin extension
            // `runReadAction { }`, because the extension is deprecated in IntelliJ 2026.1
            // early-access builds. This form stays non-deprecated across 2024.2 through
            // 2026.1 and matches the underlying platform API one-to-one.
            ApplicationManager.getApplication().runReadAction<Boolean> {
                val bytes = file.inputStream.use { stream ->
                    stream.readNBytes(MAX_DETECTION_BYTES)
                }
                val text = String(bytes, Charsets.UTF_8)
                val format = SopsFormat.from_file(file)
                val result = is_sops_content(text, format)
                SopsLog.debug("SopsDetector.is_sops_file(${file.name}, format=$format): $result")
                result
            }
        } catch (e: Exception) {
            SopsLog.warn("Failed to check SOPS content for ${file.name}", e)
            false
        }
    }

    fun is_sops_content(text: String, format: SopsFormat): Boolean {
        return when (format) {
            SopsFormat.DOTENV -> dotenv_keywords.all { text.contains(it) }
            SopsFormat.INI, SopsFormat.TOML ->
                text.contains("[sops]") && (text.contains("mac=") || text.contains("mac = "))
            SopsFormat.JSON ->
                text.contains("\"sops\"") && (text.contains("\"mac\"") || text.contains("\"lastmodified\""))
            // YAML and BINARY share the same wrapper (binary SOPS output is a YAML envelope).
            SopsFormat.YAML, SopsFormat.BINARY -> has_yaml_sops_block(text)
        }
    }

    /**
     * For YAML-family formats we look for a `sops:` key at column 0 (top-level) plus either
     * the `mac:` or `lastmodified:` sibling. This eliminates the earlier false-positive case
     * where any YAML containing version/lastmodified/sops substrings was flagged as SOPS.
     */
    private fun has_yaml_sops_block(text: String): Boolean {
        val has_sops_block = text.lineSequence().any { line ->
            line.startsWith("sops:") || line.startsWith("\"sops\":")
        }
        if (!has_sops_block) return false
        val has_mac_or_lastmodified = text.contains("mac:") ||
            text.contains("lastmodified:") ||
            text.contains("\"mac\":") ||
            text.contains("\"lastmodified\":")
        return has_mac_or_lastmodified
    }

    fun is_sops_document(document: Document): Boolean {
        val file = FileDocumentManager.getInstance().getFile(document) ?: return false
        return is_sops_file(file)
    }
}
