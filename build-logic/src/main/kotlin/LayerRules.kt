/**
 * The layer rules of ADR-CVM-003, with ADR-CVM-007's capabilities:
 * - the core never depends on a plugin or a capability;
 * - a plugin depends on the plugin interface and on capabilities, and on nothing else; never on an
 *   adapter, which the app wires;
 * - a capability depends on no module of ours, and an adapter only on the capability it adapts;
 * - no plugin depends on another;
 * - a plugin's rules, its decisions in plain Kotlin at `:plugins:<name>:rules`, depend on no
 *   module of ours, and only that plugin depends on them; the app wires the plugin, not its rules;
 * - the app is the only module that sees them all.
 *
 * A module that is in no layer is refused too, so a new module is placed in one on purpose
 * rather than slipping past the rules.
 */
object LayerRules {
    private const val API = ":plugin-api"

    private enum class Layer { APP, CORE, API, PLUGIN, RULES, CAPABILITY, ADAPTER, GROUP }

    private fun depth(path: String) = path.count { it == ':' }

    private fun layerOf(path: String): Layer? =
        when {
            path == ":app" -> Layer.APP
            path == ":core" -> Layer.CORE
            path == API -> Layer.API
            path == ":plugins" || path == ":capabilities" -> Layer.GROUP
            path.startsWith(":plugins:") && depth(path) == 2 -> Layer.PLUGIN
            path.startsWith(":plugins:") && depth(path) == 3 && path.endsWith(":rules") -> Layer.RULES
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
            Layer.APP ->
                if (target == Layer.RULES) "$from depends on $to; the app wires a plugin's entry, never its rules" else null
            Layer.CORE -> core(from, to, target)
            Layer.PLUGIN -> plugin(from, to, target)
            Layer.RULES -> "$from depends on $to; a plugin's rules depend on no module of ours"
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
            Layer.RULES -> "$from depends on $to; the core never depends on a plugin's rules"
            Layer.CAPABILITY, Layer.ADAPTER -> "$from depends on $to; the core never depends on a capability"
            else -> "$from depends on $to; the core depends only on $API"
        }

    private fun plugin(from: String, to: String, target: Layer?): String? =
        when (target) {
            Layer.API, Layer.CAPABILITY -> null
            Layer.RULES -> if (to == "$from:rules") null else "$from depends on $to; a plugin depends only on its own rules"
            Layer.PLUGIN -> "$from depends on $to; no plugin depends on another"
            Layer.ADAPTER -> "$from depends on $to; a plugin never depends on an adapter; the app wires adapters"
            else -> "$from depends on $to; a plugin depends only on $API and capabilities"
        }
}
