import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

private const val ANDROID = "http://schemas.android.com/apk/res/android"

/** What a manifest asks the phone for: its permissions, and the types of its foreground services. */
data class ManifestEntries(val permissions: Set<String>, val serviceTypes: Set<String>) {
    companion object {
        /** Reads `uses-permission`, `uses-permission-sdk-23` and each service's `foregroundServiceType`. */
        fun of(xml: String): ManifestEntries {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                // A manifest is our own file, but nothing it says should reach outside it.
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            }
            val document = factory.newDocumentBuilder().parse(xml.byteInputStream())
            fun named(tag: String) = document.getElementsByTagName(tag).let { list ->
                (0 until list.length).map { list.item(it) as Element }
            }
            val permissions = (named("uses-permission") + named("uses-permission-sdk-23"))
                .map { it.getAttributeNS(ANDROID, "name") }
            val types = named("service")
                .flatMap { it.getAttributeNS(ANDROID, "foregroundServiceType").split('|') }
                .filter { it.isNotBlank() }
            return ManifestEntries(permissions.toSet(), types.toSet())
        }
    }
}

/**
 * ADR-CVM-007: a module left out of the build adds nothing to the app. The merged manifest holds
 * every library's entries too, so it may ask only for what the app or a module it took declares in
 * its own manifest; a library's permission is refused until one of ours declares it, with a reason.
 */
object ManifestCheck {
    private const val NOBODY = "which neither the app nor a module it took declares"

    /** One line for each permission or service type in [merged] that none of [declared] names. */
    fun undeclared(merged: ManifestEntries, declared: List<ManifestEntries>): List<String> {
        val permissions = declared.flatMap { it.permissions }.toSet()
        val types = declared.flatMap { it.serviceTypes }.toSet()
        return (merged.permissions - permissions).sorted().map { "the app asks for $it, $NOBODY" } +
            (merged.serviceTypes - types).sorted().map { "the app runs a service of type $it, $NOBODY" }
    }
}
