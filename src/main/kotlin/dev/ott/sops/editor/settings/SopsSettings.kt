package dev.ott.sops.editor.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.SystemProperties
import com.intellij.util.system.OS
import java.nio.file.Files
import java.nio.file.Path

@Service(Service.Level.APP)
@State(name = "SopsEditorSettings", storages = [Storage("SopsEditor.xml")])
class SopsSettings : PersistentStateComponent<SopsSettings.State> {

    data class State(
        var sops_path: String = detect_sops_path(),
        var age_key_file: String = default_age_key_path(),
        var auto_encrypt_on_save: Boolean = true,
        var encrypted_side_editable: Boolean = false,
        var custom_environment: String = "",
        var log_path: String = default_log_path(),
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    var sops_path: String
        get() = state.sops_path
        set(value) { state.sops_path = value }

    var age_key_file: String
        get() = state.age_key_file
        set(value) { state.age_key_file = value }

    var auto_encrypt_on_save: Boolean
        get() = state.auto_encrypt_on_save
        set(value) { state.auto_encrypt_on_save = value }

    var encrypted_side_editable: Boolean
        get() = state.encrypted_side_editable
        set(value) { state.encrypted_side_editable = value }

    var custom_environment: String
        get() = state.custom_environment
        set(value) { state.custom_environment = value }

    var log_path: String
        get() = state.log_path
        set(value) { state.log_path = value }

    fun get_environment(): Map<String, String> {
        val env = mutableMapOf<String, String>()

        if (age_key_file.isNotBlank()) {
            env["SOPS_AGE_KEY_FILE"] = resolve_path(age_key_file)
        }

        for (line in custom_environment.lines()) {
            val trimmed = line.trim()
            if (trimmed.isBlank() || trimmed.startsWith("#")) continue
            val idx = trimmed.indexOf('=')
            if (idx > 0) {
                env[trimmed.substring(0, idx).trim()] = trimmed.substring(idx + 1).trim()
            }
        }

        return env
    }

    companion object {
        fun get_instance(): SopsSettings {
            return ApplicationManager.getApplication().getService(SopsSettings::class.java)
        }

        /**
         * Searches for the sops binary on the user's PATH and a short list of well-known
         * install locations per OS. Returns the first match, or the bare name `sops` if
         * nothing could be found — the user will see a clear error via the Verify button
         * in settings and can configure a full path manually.
         */
        private fun detect_sops_path(): String {
            val binary_name = if (OS.CURRENT == OS.Windows) "sops.exe" else "sops"

            // PATH lookup first.
            val path_env = System.getenv("PATH") ?: ""
            val separator = if (OS.CURRENT == OS.Windows) ";" else ":"
            for (dir in path_env.split(separator)) {
                if (dir.isBlank()) continue
                val candidate = try {
                    Path.of(dir, binary_name)
                } catch (_: Exception) {
                    continue
                }
                if (Files.exists(candidate) && Files.isExecutable(candidate)) {
                    return candidate.toAbsolutePath().toString()
                }
            }

            // Common per-OS install locations as fallback.
            val fallbacks = when {
                OS.CURRENT == OS.Windows -> listOf(
                    "C:\\ProgramData\\chocolatey\\bin\\sops.exe",
                    "C:\\Program Files\\sops\\sops.exe",
                )
                OS.CURRENT == OS.macOS -> listOf(
                    "/opt/homebrew/bin/sops",
                    "/usr/local/bin/sops",
                )
                else -> listOf(
                    "/usr/local/bin/sops",
                    "/usr/bin/sops",
                )
            }
            for (path in fallbacks) {
                if (Files.exists(Path.of(path))) return path
            }

            return "sops"
        }

        private fun default_age_key_path(): String {
            return when {
                OS.CURRENT == OS.Windows -> "%APPDATA%\\sops\\age\\keys.txt"
                else -> "~/.config/sops/age/keys.txt"
            }
        }

        private fun default_log_path(): String {
            return Path.of(System.getProperty("java.io.tmpdir"), "sops-editor-plugin").toString()
        }

        /**
         * Expands user-facing paths into absolute paths: `~`, `%APPDATA%`, `%USERPROFILE%`,
         * `$HOME` and `${VAR}` forms are all supported. Returns the input unchanged if no
         * substitution applies.
         */
        fun resolve_path(path: String): String {
            var result = path
                .replace("~", SystemProperties.getUserHome())
                .replace("%APPDATA%", System.getenv("APPDATA") ?: "")
                .replace("%USERPROFILE%", System.getenv("USERPROFILE") ?: "")
                .replace("\$HOME", SystemProperties.getUserHome())

            // Expand ${VAR} references against the process environment.
            val dollar_brace = Regex("\\\$\\{([A-Za-z_][A-Za-z0-9_]*)}")
            result = dollar_brace.replace(result) { match ->
                System.getenv(match.groupValues[1]) ?: match.value
            }

            return result
        }
    }
}
