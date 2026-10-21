package dev.ott.sops.editor

/**
 * Pure helpers for passing text to sops through stdin instead of a temporary file. Kept free of
 * platform dependencies so version parsing and argument building are unit testable.
 */
internal data class SopsVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
) : Comparable<SopsVersion> {

    override fun compareTo(other: SopsVersion): Int {
        return compareValuesBy(this, other, SopsVersion::major, SopsVersion::minor, SopsVersion::patch)
    }

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        // sops 3.10.0 reads stdin in the encrypt and decrypt subcommands without a device path
        // like /dev/stdin, which does not exist on Windows; --filename-override exists since 3.9.0.
        val STDIN_MINIMUM = SopsVersion(3, 10, 0)

        // Matches "sops 3.12.1", "sops 3.7.3 (latest)" and "sops v3.10.0" at the start of a line;
        // update notices printed after the first line are ignored.
        private val version_regex = Regex("(?m)^\\s*sops\\s+v?(\\d+)\\.(\\d+)\\.(\\d+)")

        fun parse(output: String): SopsVersion? {
            val match = version_regex.find(output) ?: return null
            val (major, minor, patch) = match.destructured
            return SopsVersion(major.toInt(), minor.toInt(), patch.toInt())
        }
    }
}

internal object SopsStdinArgs {

    // Without a file argument the subcommands read stdin. The override path drives the
    // .sops.yaml creation_rules match for encrypt, the types are passed explicitly so
    // content-detected files with unusual extensions use the right store.
    fun encrypt(format: SopsFormat, override_path: String): List<String> {
        return listOf("encrypt") + type_args(format) + listOf("--filename-override", override_path)
    }

    fun decrypt(format: SopsFormat, override_path: String?): List<String> {
        val override = if (override_path != null) listOf("--filename-override", override_path) else emptyList()
        return listOf("decrypt") + type_args(format) + override
    }

    /**
     * True when sops refused the stdin call itself rather than the content: sops 3.9 knows the
     * subcommands but stops with "no file specified" (exit code 100), and a build without
     * --filename-override fails with the usage error of its CLI library.
     */
    fun rejects_stdin(exit_code: Int, stderr: String): Boolean {
        if (exit_code == 0) {
            return false
        }
        return exit_code == NO_FILE_SPECIFIED_EXIT_CODE ||
            stderr.contains("no file specified", ignoreCase = true) ||
            stderr.contains("flag provided but not defined", ignoreCase = true)
    }

    private const val NO_FILE_SPECIFIED_EXIT_CODE = 100

    private fun type_args(format: SopsFormat): List<String> {
        return listOf("--input-type", format.sops_type, "--output-type", format.sops_type)
    }
}
