/**
 * ADR-CVM-007: which plugins a build takes. One Gradle property, `cvPlugins`, lists them, such as
 * `-PcvPlugins=status,sms`. With none given, the build takes `status` alone. Anyone's own list goes
 * in their own `~/.gradle/gradle.properties`, outside the repository.
 */
object ModulePicks {
    const val PROPERTY = "cvPlugins"
    const val DEFAULT = "status"

    /** Every plugin folder, as CI builds it, so no plugin's entries go unchecked. */
    const val ALL = "all"

    /** The protocol's pattern for a plugin's name; the folder, package and name are one string. */
    private val NAME = Regex("[a-z][a-z0-9_]{0,31}")

    /** The plugins [given] names, in order and once each; refused when a name is not in [known]. */
    fun picked(given: String?, known: Set<String>): List<String> {
        require(ALL !in known) { "a plugin folder is named $ALL, the word for every plugin; rename the folder" }
        val listed = (given ?: DEFAULT).split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        // `all` is expanded first, so every folder it takes goes through the same name check.
        val names = if (listed == listOf(ALL)) known.sorted() else listed
        require(names.isNotEmpty()) { "$PROPERTY names no plugin; give at least one, such as $DEFAULT" }
        names.firstOrNull { !NAME.matches(it) }?.let {
            throw IllegalArgumentException(
                "$PROPERTY names $it, which is not a plugin name: a small letter, then at most 31 small letters, digits or _",
            )
        }
        val unknown = names.filterNot { it in known }
        require(unknown.isEmpty()) {
            "$PROPERTY names ${unknown.joinToString()}, which is not a module; " +
                "the modules are: ${known.sorted().joinToString()}"
        }
        return names
    }
}
