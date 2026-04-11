package dev.ott.sops.editor

import com.intellij.openapi.vfs.VirtualFile

/**
 * The file formats the plugin understands. `display_name_key` points at the localized
 * human-facing label in `SopsBundle`, `template_key` points at the starter content used
 * when creating a new SOPS file of this format.
 */
enum class SopsFormat(
    val extensions: List<String>,
    val sops_type: String,
    val display_name_key: String,
    val template_key: String,
) {
    DOTENV(listOf(".env"), "dotenv", "format.dotenv", "template.dotenv"),
    YAML(listOf(".yaml", ".yml"), "yaml", "format.yaml", "template.yaml"),
    JSON(listOf(".json"), "json", "format.json", "template.json"),
    INI(listOf(".ini"), "ini", "format.ini", "template.ini"),
    TOML(listOf(".toml"), "toml", "format.toml", "template.toml"),
    BINARY(listOf(".bin"), "binary", "format.binary", "template.binary");

    val primary_extension: String
        get() = extensions.first()

    val display_name: String
        get() = SopsBundle.message(display_name_key)

    val template: String
        get() = SopsBundle.message(template_key)

    companion object {
        fun from_file(file: VirtualFile): SopsFormat {
            val name = file.name.lowercase()
            return entries.firstOrNull { format ->
                format.extensions.any { ext -> name.endsWith(ext) }
            } ?: YAML
        }

        fun from_extension(extension: String): SopsFormat {
            val ext = if (extension.startsWith(".")) extension else ".$extension"
            return entries.firstOrNull { format ->
                format.extensions.any { it == ext.lowercase() }
            } ?: YAML
        }
    }
}
