package dev.ott.sops.editor

import java.text.MessageFormat
import java.util.Locale
import java.util.MissingResourceException
import java.util.ResourceBundle

/**
 * Localization layer for all user-facing strings. The plugin ships English-only — IntelliJ
 * settings dialogs cannot rerender existing JLabels after a language change without a
 * restart, which makes a runtime language toggle confusing UX. We sidestep that by loading
 * exactly one bundle (`messages/SopsBundle.properties`) at class init time and never
 * touching it again.
 *
 * Adding a new language later means: ship `SopsBundle_<lang>.properties`, switch the
 * lookup to `Locale.getDefault()` or a settings field, and accept the IDE-restart UX.
 */
object SopsBundle {
    private const val BUNDLE = "messages.SopsBundle"

    // Locale.ROOT loads only the unsuffixed properties file and skips any locale-specific
    // siblings even if they exist on the classpath. Keeps behavior deterministic across
    // user OS locales.
    private val bundle: ResourceBundle = ResourceBundle.getBundle(BUNDLE, Locale.ROOT)

    fun message(key: String, vararg params: Any): String {
        val pattern = try {
            bundle.getString(key)
        } catch (_: MissingResourceException) {
            key
        }
        return if (params.isEmpty()) pattern else MessageFormat.format(pattern, *params)
    }
}
