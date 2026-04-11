package dev.ott.sops.editor.settings

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import dev.ott.sops.editor.SopsBundle
import dev.ott.sops.editor.SopsLog
import dev.ott.sops.editor.SopsResult
import dev.ott.sops.editor.SopsWrapper
import kotlinx.coroutines.runBlocking
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.io.File
import javax.swing.*

class SopsSettingsConfigurable : Configurable {

    private var sops_path_field: TextFieldWithBrowseButton? = null
    private var sops_verify_area: JBTextArea? = null
    private var sops_verify_icon: JBLabel? = null
    private var age_key_field: TextFieldWithBrowseButton? = null
    private var age_verify_area: JBTextArea? = null
    private var age_verify_icon: JBLabel? = null
    private var auto_encrypt_checkbox: JBCheckBox? = null
    private var encrypted_editable_checkbox: JBCheckBox? = null
    private var environment_area: JBTextArea? = null
    private var log_path_field: TextFieldWithBrowseButton? = null
    private var validate_result_area: JBTextArea? = null
    private var panel: JPanel? = null

    override fun getDisplayName(): String = SopsBundle.message("settings.title")

    override fun createComponent(): JComponent {
        val settings = SopsSettings.get_instance()

        // --- SOPS Path with Verify ---
        sops_path_field = create_file_browse_field(
            initial = settings.sops_path,
            descriptor = FileChooserDescriptor(true, false, true, true, false, false)
                .withTitle(SopsBundle.message("settings.sops.path"))
                .withDescription(SopsBundle.message("settings.sops.path.tooltip")),
        )
        sops_verify_icon = JBLabel()
        sops_verify_area = JBTextArea().apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            isOpaque = false
            border = JBUI.Borders.empty()
            font = JBUI.Fonts.smallFont()
        }
        val sops_verify_button = JButton(SopsBundle.message("settings.verify")).apply {
            addActionListener { verify_sops() }
        }
        val sops_field_row = create_field_with_button(sops_path_field!!, sops_verify_button)

        // --- Age Key Path with Verify ---
        age_key_field = create_file_browse_field(
            initial = settings.age_key_file,
            descriptor = FileChooserDescriptor(true, false, true, true, false, false)
                .withTitle(SopsBundle.message("settings.age.key.file"))
                .withDescription(SopsBundle.message("settings.age.key.file.tooltip")),
        )
        age_verify_icon = JBLabel()
        age_verify_area = JBTextArea().apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            isOpaque = false
            border = JBUI.Borders.empty()
            font = JBUI.Fonts.smallFont()
        }
        val age_verify_button = JButton(SopsBundle.message("settings.verify")).apply {
            addActionListener { verify_age_key() }
        }
        val age_field_row = create_field_with_button(age_key_field!!, age_verify_button)

        // --- Checkboxes ---
        auto_encrypt_checkbox = JBCheckBox(
            SopsBundle.message("settings.auto.encrypt"),
            settings.auto_encrypt_on_save
        )
        encrypted_editable_checkbox = JBCheckBox(
            SopsBundle.message("settings.encrypted.editable"),
            settings.encrypted_side_editable
        )

        // --- Custom environment ---
        environment_area = JBTextArea(settings.custom_environment, 5, 50).apply {
            border = JBUI.Borders.empty(4)
        }

        // --- Log path ---
        log_path_field = create_file_browse_field(
            initial = settings.log_path,
            descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
                .withTitle(SopsBundle.message("settings.log.path"))
                .withDescription(SopsBundle.message("settings.log.path.tooltip")),
        )

        // --- Validate Setup ---
        validate_result_area = JBTextArea("", 10, 60).apply {
            isEditable = false
            font = JBUI.Fonts.create("JetBrains Mono", 12)
            border = JBUI.Borders.empty(8)
        }
        val validate_button = JButton(SopsBundle.message("settings.validate.setup")).apply {
            addActionListener { validate_full_setup() }
        }
        val validate_panel = JPanel(BorderLayout(0, 8)).apply {
            add(validate_button, BorderLayout.NORTH)
            add(JScrollPane(validate_result_area).apply {
                preferredSize = java.awt.Dimension(0, 200)
            }, BorderLayout.CENTER)
            border = JBUI.Borders.emptyTop(4)
        }

        // --- Log buttons ---
        val open_log_button = JButton(SopsBundle.message("settings.open.log")).apply {
            addActionListener {
                try {
                    java.awt.Desktop.getDesktop().open(SopsLog.log_file.toFile())
                } catch (_: Exception) {
                    try {
                        java.awt.Desktop.getDesktop().open(SopsLog.log_dir.toFile())
                    } catch (_: Exception) {}
                }
            }
        }
        val clear_log_button = JButton(SopsBundle.message("settings.clear.log")).apply {
            addActionListener {
                SopsLog.clear()
                validate_result_area?.text = SopsBundle.message("settings.log.cleared")
            }
        }
        val log_buttons_panel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            add(open_log_button)
            add(clear_log_button)
        }

        // --- Build form ---
        panel = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel(SopsBundle.message("settings.sops.path")), sops_field_row, 1, false)
            .addComponent(create_verify_result_row(sops_verify_icon!!, sops_verify_area!!), 0)
            .addLabeledComponent(JBLabel(SopsBundle.message("settings.age.key.file")), age_field_row, 1, false)
            .addComponent(create_verify_result_row(age_verify_icon!!, age_verify_area!!), 0)
            .addSeparator(8)
            .addComponent(auto_encrypt_checkbox!!, 1)
            .addComponent(encrypted_editable_checkbox!!, 1)
            .addSeparator(8)
            .addLabeledComponent(
                JBLabel(SopsBundle.message("settings.environment")),
                JScrollPane(environment_area).apply {
                    preferredSize = java.awt.Dimension(0, 100)
                },
                1,
                true
            )
            .addSeparator(8)
            .addComponent(validate_panel, 1)
            .addSeparator(8)
            .addLabeledComponent(JBLabel(SopsBundle.message("settings.log.path")), log_path_field!!, 1, false)
            .addComponent(log_buttons_panel, 0)
            .addComponentFillVertically(JPanel(), 0)
            .panel

        return panel!!
    }

    /**
     * Builds a text field with a browse button that opens a file chooser. The browse button
     * wires up a plain Swing action listener rather than calling `addBrowseFolderListener`,
     * which is scheduled for removal in recent IntelliJ versions and whose drop-in
     * replacement only exists from 2024.3 onwards. This manual wiring works on every
     * platform version from 2024.2 on and carries no deprecation debt.
     */
    private fun create_file_browse_field(
        initial: String,
        descriptor: FileChooserDescriptor,
    ): TextFieldWithBrowseButton {
        return TextFieldWithBrowseButton().also { field ->
            field.text = initial
            field.addActionListener {
                FileChooser.chooseFile(descriptor, null, null)?.let { chosen ->
                    field.text = chosen.path
                }
            }
        }
    }

    private fun create_field_with_button(
        field: TextFieldWithBrowseButton,
        button: JButton,
    ): JPanel {
        return JPanel(BorderLayout(4, 0)).apply {
            add(field, BorderLayout.CENTER)
            add(button, BorderLayout.EAST)
        }
    }

    private fun create_verify_result_row(icon: JBLabel, area: JBTextArea): JPanel {
        return JPanel(BorderLayout(4, 0)).apply {
            border = JBUI.Borders.emptyLeft(24)
            add(icon, BorderLayout.WEST)
            add(area, BorderLayout.CENTER)
        }
    }

    private fun verify_sops() {
        val icon = sops_verify_icon ?: return
        val area = sops_verify_area ?: return
        icon.icon = null
        area.text = "..."

        val path_override = sops_path_field?.text ?: return

        object : Task.Backgroundable(null, SopsBundle.message("settings.verify"), false) {
            private var result: SopsResult? = null
            private var thrown: Throwable? = null

            override fun run(indicator: ProgressIndicator) {
                try {
                    result = runBlocking { SopsWrapper.version(path_override) }
                } catch (t: Throwable) {
                    thrown = t
                }
            }

            override fun onFinished() {
                val ex = thrown
                val res = result
                when {
                    ex != null -> {
                        icon.icon = AllIcons.General.Error
                        area.foreground = JBColor.RED
                        area.text = SopsLog.sanitize_for_log(ex.message ?: SopsBundle.message("settings.generic.error"))
                    }
                    res != null && res.success -> {
                        icon.icon = AllIcons.General.InspectionsOK
                        area.foreground = JBColor.namedColor("Label.successForeground", JBColor(0x368746, 0x5FAD65))
                        area.text = res.stdout.trim()
                    }
                    else -> {
                        icon.icon = AllIcons.General.Error
                        area.foreground = JBColor.RED
                        area.text = SopsBundle.message("settings.version.error")
                    }
                }
            }
        }.queue()
    }

    private fun verify_age_key() {
        val icon = age_verify_icon ?: return
        val area = age_verify_area ?: return
        val path = age_key_field?.text ?: ""
        val resolved = SopsSettings.resolve_path(path)
        val file = File(resolved)

        if (!file.exists()) {
            icon.icon = AllIcons.General.Error
            area.foreground = JBColor.RED
            area.text = SopsBundle.message("settings.age.key.not.found")
            return
        }

        if (!file.canRead()) {
            icon.icon = AllIcons.General.Warning
            area.foreground = JBColor.YELLOW
            area.text = SopsBundle.message("settings.age.key.not.readable")
            return
        }

        val content = file.readText()
        val has_secret_key = content.contains("AGE-SECRET-KEY-")
        if (has_secret_key) {
            val key_count = content.lines().count { it.startsWith("AGE-SECRET-KEY-") }
            icon.icon = AllIcons.General.InspectionsOK
            area.foreground = JBColor.namedColor("Label.successForeground", JBColor(0x368746, 0x5FAD65))
            area.text = SopsBundle.message("settings.age.key.valid", key_count)
        } else {
            icon.icon = AllIcons.General.Warning
            area.foreground = JBColor.YELLOW
            area.text = SopsBundle.message("settings.age.key.no.secret")
        }
    }

    private fun validate_full_setup() {
        val area = validate_result_area ?: return
        val sops_path_input = sops_path_field?.text ?: ""
        val age_path_input = age_key_field?.text ?: ""
        area.text = "..."

        object : Task.Backgroundable(null, SopsBundle.message("settings.validate.setup"), false) {
            private var output: String = ""

            override fun run(indicator: ProgressIndicator) {
                val results = mutableListOf<String>()
                val ok = SopsBundle.message("validate.ok")
                val error = SopsBundle.message("validate.error")
                val warning = SopsBundle.message("validate.warning")
                val info = SopsBundle.message("validate.info")

                // 1. SOPS binary
                results.add("=== ${SopsBundle.message("validate.sops.binary")} ===")
                val sops_file = File(sops_path_input)
                if (sops_file.exists() && sops_file.canExecute()) {
                    results.add("[$ok] ${SopsBundle.message("validate.sops.found", sops_path_input)}")
                    try {
                        val version_result = runBlocking { SopsWrapper.version(sops_path_input) }
                        if (version_result.success) {
                            results.add("[$ok] ${SopsBundle.message("validate.sops.version", version_result.stdout.trim())}")
                        } else {
                            val sanitized = SopsLog.sanitize_for_log(version_result.stderr.trim())
                            results.add("[$error] ${SopsBundle.message("validate.sops.version.failed", sanitized)}")
                        }
                    } catch (e: Exception) {
                        results.add("[$error] ${SopsLog.sanitize_for_log(e.message ?: SopsBundle.message("settings.generic.error"))}")
                    }
                } else if (sops_file.exists()) {
                    results.add("[$warning] ${SopsBundle.message("validate.sops.not.executable", sops_path_input)}")
                } else {
                    results.add("[$error] ${SopsBundle.message("validate.sops.not.found", sops_path_input)}")
                }

                results.add("")

                // 2. Age key
                results.add("=== ${SopsBundle.message("validate.age.key")} ===")
                val resolved_age = SopsSettings.resolve_path(age_path_input)
                val age_file = File(resolved_age)
                if (age_file.exists()) {
                    results.add("[$ok] ${SopsBundle.message("validate.key.found", resolved_age)}")
                    if (age_file.canRead()) {
                        val content = age_file.readText()
                        val secret_keys = content.lines().count { it.startsWith("AGE-SECRET-KEY-") }
                        val public_keys = content.lines().count { it.startsWith("# public key:") }
                        if (secret_keys > 0) {
                            results.add("[$ok] ${SopsBundle.message("validate.key.secrets", secret_keys, public_keys)}")
                        } else {
                            results.add("[$warning] ${SopsBundle.message("validate.key.no.secrets")}")
                        }
                    } else {
                        results.add("[$error] ${SopsBundle.message("validate.key.not.readable")}")
                    }
                } else {
                    results.add("[$error] ${SopsBundle.message("validate.key.not.found", resolved_age)}")
                }

                results.add("")

                // 3. age binary (optional)
                results.add("=== ${SopsBundle.message("validate.age.binary")} ===")
                val age_binary = find_binary("age")
                if (age_binary != null) {
                    results.add("[$ok] ${SopsBundle.message("validate.age.found", age_binary)}")
                } else {
                    results.add("[$info] ${SopsBundle.message("validate.age.not.found")}")
                }

                results.add("")

                // 4. Summary
                results.add("=== ${SopsBundle.message("validate.summary")} ===")
                val errors = results.count { it.startsWith("[$error]") }
                val warnings = results.count { it.startsWith("[$warning]") }
                if (errors == 0 && warnings == 0) {
                    results.add(SopsBundle.message("validate.all.ok"))
                } else {
                    if (errors > 0) results.add(SopsBundle.message("validate.errors", errors))
                    if (warnings > 0) results.add(SopsBundle.message("validate.warnings", warnings))
                }

                output = results.joinToString("\n")
            }

            override fun onFinished() {
                area.text = output
            }
        }.queue()
    }

    private fun find_binary(name: String): String? {
        val path_env = System.getenv("PATH") ?: return null
        val separator = if (System.getProperty("os.name").lowercase().contains("win")) ";" else ":"
        val extensions = if (System.getProperty("os.name").lowercase().contains("win"))
            listOf(".exe", ".cmd", ".bat", "") else listOf("")

        for (dir in path_env.split(separator)) {
            for (ext in extensions) {
                val file = File(dir, "$name$ext")
                if (file.exists() && file.canExecute()) return file.absolutePath
            }
        }
        return null
    }

    override fun isModified(): Boolean {
        val settings = SopsSettings.get_instance()
        return sops_path_field?.text != settings.sops_path ||
                age_key_field?.text != settings.age_key_file ||
                auto_encrypt_checkbox?.isSelected != settings.auto_encrypt_on_save ||
                encrypted_editable_checkbox?.isSelected != settings.encrypted_side_editable ||
                environment_area?.text != settings.custom_environment ||
                log_path_field?.text != settings.log_path
    }

    override fun apply() {
        val settings = SopsSettings.get_instance()
        settings.sops_path = sops_path_field?.text ?: settings.sops_path
        settings.age_key_file = age_key_field?.text ?: settings.age_key_file
        settings.auto_encrypt_on_save = auto_encrypt_checkbox?.isSelected ?: true
        settings.encrypted_side_editable = encrypted_editable_checkbox?.isSelected ?: false
        settings.custom_environment = environment_area?.text ?: ""
        settings.log_path = log_path_field?.text ?: settings.log_path
    }

    override fun reset() {
        val settings = SopsSettings.get_instance()
        sops_path_field?.text = settings.sops_path
        age_key_field?.text = settings.age_key_file
        auto_encrypt_checkbox?.isSelected = settings.auto_encrypt_on_save
        encrypted_editable_checkbox?.isSelected = settings.encrypted_side_editable
        environment_area?.text = settings.custom_environment
        log_path_field?.text = settings.log_path
        sops_verify_area?.text = ""
        sops_verify_icon?.icon = null
        age_verify_area?.text = ""
        age_verify_icon?.icon = null
        validate_result_area?.text = ""
    }

    override fun disposeUIResources() {
        sops_path_field = null
        sops_verify_area = null
        sops_verify_icon = null
        age_key_field = null
        age_verify_area = null
        age_verify_icon = null
        auto_encrypt_checkbox = null
        encrypted_editable_checkbox = null
        environment_area = null
        log_path_field = null
        validate_result_area = null
        panel = null
    }
}
