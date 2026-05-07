package dev.ott.sops.editor

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.vfs.VirtualFile

/**
 * Detects whether a file is SOPS-encrypted by looking for the metadata markers that SOPS
 * writes into every format. Only a head + tail window of `DETECTION_WINDOW` bytes is read,
 * so large payloads don't cause unbounded reads while binary-mode envelopes still match
 * (their `sops:` block sits at the end of the file, past the head). The structured-format
 * heuristic requires *both* a `sops:` / `[sops]` container and one of the mac / lastmodified
 * fields, which avoids false positives from user content that happens to mention a keyword.
 */
object SopsDetector {

    private val dotenv_keywords = listOf("sops_version=", "sops_lastmodified=", "sops_mac=")

    fun is_sops_file(file: VirtualFile): Boolean {
        return detect_sops_format_from_file(file) != null
    }

    /**
     * Reads the head and the tail of the file and decides which SOPS format it is, if any.
     * Tail matters for binary-mode files where the metadata `sops` block sits at the end of
     * the JSON envelope; a head-only read would miss it on payloads larger than the head
     * window.
     */
    fun detect_sops_format_from_file(file: VirtualFile): SopsFormat? {
        return try {
            // Using Application.runReadAction directly rather than the Kotlin extension
            // `runReadAction { }`, because the extension is deprecated in IntelliJ 2026.1
            // early-access builds. This form stays non-deprecated across 2024.2 through
            // 2026.1 and matches the underlying platform API one-to-one.
            ApplicationManager.getApplication().runReadAction<SopsFormat?> {
                val text = read_head_and_tail(file)
                val result = detect_sops_format(text)
                SopsLog.debug("SopsDetector.detect_sops_format_from_file(${file.name}): $result")
                result
            }
        } catch (e: Exception) {
            SopsLog.warn("Failed to detect SOPS format for ${file.name}", e)
            null
        }
    }

    /**
     * Reads up to DETECTION_WINDOW bytes from the start AND DETECTION_WINDOW bytes from the
     * end of the file. For files smaller than the combined window the whole file is read.
     * Concatenation uses a newline separator so line-anchored probes (the YAML/INI ones)
     * stay correct.
     */
    private fun read_head_and_tail(file: VirtualFile): String {
        file.inputStream.use { stream ->
            val total = file.length
            if (total <= DETECTION_WINDOW.toLong() * 2L) {
                return String(stream.readAllBytes(), Charsets.UTF_8)
            }
            val head = stream.readNBytes(DETECTION_WINDOW)
            val skip = total - DETECTION_WINDOW.toLong() - head.size.toLong()
            stream.skipNBytes(skip)
            val tail = stream.readNBytes(DETECTION_WINDOW)
            return String(head, Charsets.UTF_8) + "\n" + String(tail, Charsets.UTF_8)
        }
    }

    private const val DETECTION_WINDOW = 16 * 1024  // 16 KB head + 16 KB tail = 32 KB max

    /**
     * Pure-content format detection — tries each format probe in order of marker
     * specificity. DOTENV first because its three required markers can't appear anywhere
     * else; BINARY before JSON because a binary envelope would also satisfy the JSON probe.
     */
    fun detect_sops_format(text: String): SopsFormat? {
        val ordered = listOf(
            SopsFormat.DOTENV,
            SopsFormat.BINARY,
            SopsFormat.INI,
            SopsFormat.JSON,
            SopsFormat.YAML,
        )
        return ordered.firstOrNull { format -> is_sops_content(text, format) }
    }

    fun is_sops_content(text: String, format: SopsFormat): Boolean {
        return when (format) {
            SopsFormat.DOTENV -> dotenv_keywords.all { text.contains(it) }
            SopsFormat.INI ->
                text.contains("[sops]") && (text.contains("mac=") || text.contains("mac = "))
            SopsFormat.JSON ->
                text.contains("\"sops\"") && (text.contains("\"mac\"") || text.contains("\"lastmodified\""))
            SopsFormat.YAML -> has_yaml_sops_block(text)
            // Single ENC blob in a JSON wrapper. Modern sops always produces a JSON envelope
            // for binary mode, so YAML-shaped fallbacks would just collide with the YAML probe.
            SopsFormat.BINARY -> is_binary_sops_envelope(text)
        }
    }

    /**
     * Three markers — `data: "ENC[`, `sops: {`, mac/lastmodified — keep innocent JSON
     * that happens to use those keywords from passing as a binary envelope. The closing
     * brace deliberately isn't checked: detection input may be a head + tail concatenation
     * with the actual `}` only present in the tail, so a strict `endsWith` would false-
     * negative on any SOPS file larger than the detection window.
     */
    private fun is_binary_sops_envelope(text: String): Boolean {
        // `String.trim` doesn't strip a BOM (U+FEFF), so do it explicitly. SOPS itself
        // never writes a BOM, but a user re-saving with a BOM-adding editor shouldn't
        // make the plugin lose detection silently.
        val trimmed = text.trimStart('﻿').trim()
        if (!trimmed.startsWith("{")) return false
        val has_data_enc = data_enc_regex.containsMatchIn(trimmed)
        val has_sops_block = sops_block_regex.containsMatchIn(trimmed)
        if (!has_data_enc || !has_sops_block) return false
        return trimmed.contains("\"mac\"") || trimmed.contains("\"lastmodified\"")
    }

    private val data_enc_regex = Regex("\"data\"\\s*:\\s*\"ENC\\[")
    private val sops_block_regex = Regex("\"sops\"\\s*:\\s*\\{")

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

}
