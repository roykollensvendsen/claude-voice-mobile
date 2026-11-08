/**
 * The layer rules of ADR-CVM-003: the core never depends on a plugin, a plugin depends only on
 * the plugin interface, and no plugin depends on another. A module that is in no layer is
 * refused too, so a new module is placed in one on purpose rather than slipping past the rules.
 */
object LayerRules {
    private const val API = ":plugin-api"

    private enum class Layer { APP, CORE, API, PLUGIN, GROUP }

    private fun layerOf(path: String): Layer? =
        when {
            path == ":app" -> Layer.APP
            path == ":core" -> Layer.CORE
            path == API -> Layer.API
            path == ":plugins" -> Layer.GROUP
            path.startsWith(":plugins:") && path.count { it == ':' } == 2 -> Layer.PLUGIN
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
            Layer.CORE ->
                when (target) {
                    Layer.API -> null
                    Layer.PLUGIN -> "$from depends on $to; the core never depends on a plugin"
                    else -> "$from depends on $to; the core depends only on $API"
                }
            Layer.PLUGIN ->
                when (target) {
                    Layer.API -> null
                    Layer.PLUGIN -> "$from depends on $to; no plugin depends on another"
                    else -> "$from depends on $to; a plugin depends only on $API"
                }
            Layer.API -> "$from depends on $to; the plugin interface depends on no other module"
            Layer.GROUP -> "$from depends on $to; $from only groups the plugins"
        }
    }
}
