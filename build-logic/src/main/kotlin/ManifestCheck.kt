import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

private const val ANDROID = "http://schemas.android.com/apk/res/android"
private const val TOOLS = "http://schemas.android.com/tools"

/** What a manifest asks the phone for: its permissions, and the types of its foreground services. */
data class ManifestEntries(val permissions: Set<String>, val serviceTypes: Set<String>) {
    companion object {
        /**
         * Reads `uses-permission`, `uses-permission-sdk-23` and each service's `foregroundServiceType`,
         * with `${applicationId}` filled in as the merged manifest has it.
         */
        fun of(xml: String, applicationId: String = ""): ManifestEntries {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                // A manifest is our own file, but nothing it says should reach outside it.
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            }
            // A module's manifest names the build's id as a placeholder; the merged one has it filled in.
            val filled = xml.replace("\${applicationId}", applicationId)
            val document = factory.newDocumentBuilder().parse(filled.byteInputStream())
            fun named(tag: String) = document.getElementsByTagName(tag).let { list ->
                (0 until list.length).map { list.item(it) as Element }
            }
            // A line marked tools:node="remove" takes a library's entry out; it declares nothing.
            val permissions = (named("uses-permission") + named("uses-permission-sdk-23"))
                .filter { it.getAttributeNS(TOOLS, "node") != "remove" }
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
    // Only each module's src/main manifest is read, so a permission declared for one variant or
    // flavour alone fails here too; the message says where it belongs.
    private const val NOBODY =
        "which no module in the build declares; declare it in the src/main/AndroidManifest.xml of the module that needs it"

    /** One line for each permission or service type in [merged] that none of [declared] names. */
    fun undeclared(merged: ManifestEntries, declared: List<ManifestEntries>): List<String> {
        val permissions = declared.flatMap { it.permissions }.toSet()
        val types = declared.flatMap { it.serviceTypes }.toSet()
        return (merged.permissions - permissions).sorted().map { "the app asks for $it, $NOBODY" } +
            (merged.serviceTypes - types).sorted().map { "the app runs a service of type $it, $NOBODY" }
    }
}
