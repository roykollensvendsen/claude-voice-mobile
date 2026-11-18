/**
 * The layer rules of ADR-CVM-003, with ADR-CVM-007's capabilities:
 * - the core never depends on a plugin or a capability;
 * - a plugin depends on the plugin interface and on capabilities, and on nothing else;
 * - a capability depends on no module of ours, and an adapter only on the capability it adapts;
 * - no plugin depends on another;
 * - the app is the only module that sees them all.
 *
 * A module that is in no layer is refused too, so a new module is placed in one on purpose
 * rather than slipping past the rules.
 */
object LayerRules {
    private const val API = ":plugin-api"

    private enum class Layer { APP, CORE, API, PLUGIN, CAPABILITY, ADAPTER, GROUP }

    private fun depth(path: String) = path.count { it == ':' }

    private fun layerOf(path: String): Layer? =
        when {
            path == ":app" -> Layer.APP
            path == ":core" -> Layer.CORE
            path == API -> Layer.API
            path == ":plugins" || path == ":capabilities" -> Layer.GROUP
            path.startsWith(":plugins:") && depth(path) == 2 -> Layer.PLUGIN
            path.startsWith(":capabilities:") && depth(path) == 2 -> Layer.CAPABILITY
            path.startsWith(":capabilities:") && depth(path) == 3 -> Layer.ADAPTER
            else -> null
        }

    /** Every broken rule in `edges`, a map from each module to the modules it depends on. */
    fun violations(edges: Map<String, Collection<String>>): List<String> =
        edges.keys.sorted().flatMap { from ->
            val layer = layerOf(from)
            if (layer == null) {
                listOf("$from is in no layer; place it in LayerRules before it is built")
            } else {
                edges.getValue(from).distinct().sorted().mapNotNull { to -> broken(layer, from, to) }
            }
        }

    private fun broken(layer: Layer, from: String, to: String): String? {
        val target = layerOf(to)
        return when (layer) {
            Layer.APP -> null
            Layer.CORE -> core(from, to, target)
            Layer.PLUGIN -> plugin(from, to, target)
            Layer.CAPABILITY -> "$from depends on $to; a capability depends on no module of ours"
            Layer.ADAPTER ->
                if (to == from.substringBeforeLast(':')) {
                    null
                } else {
                    "$from depends on $to; an adapter depends only on the capability it adapts"
                }
            Layer.API -> "$from depends on $to; the plugin interface depends on no other module"
            Layer.GROUP -> "$from depends on $to; $from only groups modules"
        }
    }

    private fun core(from: String, to: String, target: Layer?): String? =
        when (target) {
            Layer.API -> null
            Layer.PLUGIN -> "$from depends on $to; the core never depends on a plugin"
            Layer.CAPABILITY, Layer.ADAPTER -> "$from depends on $to; the core never depends on a capability"
            else -> "$from depends on $to; the core depends only on $API"
        }

    private fun plugin(from: String, to: String, target: Layer?): String? =
        when (target) {
            Layer.API, Layer.CAPABILITY -> null
            Layer.PLUGIN -> "$from depends on $to; no plugin depends on another"
            else -> "$from depends on $to; a plugin depends only on $API and capabilities"
        }
}
