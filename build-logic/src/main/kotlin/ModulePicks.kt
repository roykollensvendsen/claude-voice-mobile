/**
 * ADR-CVM-007: which plugins a build takes. One Gradle property, `cvPlugins`, lists them, such as
 * `-PcvPlugins=status,sms`. With none given, the build takes `status` alone. Anyone's own list goes
 * in their own `~/.gradle/gradle.properties`, outside the repository.
 */
object ModulePicks {
    const val PROPERTY = "cvPlugins"
    const val DEFAULT = "status"

    /** The plugins [given] names, in order and once each; refused when a name is not in [known]. */
    fun picked(given: String?, known: Set<String>): List<String> {
        val names = (given ?: DEFAULT).split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        require(names.isNotEmpty()) { "$PROPERTY names no plugin; give at least one, such as $DEFAULT" }
        val unknown = names.filterNot { it in known }
        require(unknown.isEmpty()) {
            "$PROPERTY names ${unknown.joinToString()}, which is not a module; " +
                "the modules are: ${known.sorted().joinToString()}"
        }
        return names
    }
}
