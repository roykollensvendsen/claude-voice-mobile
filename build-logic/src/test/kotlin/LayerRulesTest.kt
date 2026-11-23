import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The layer rules of ADR-CVM-003 and ADR-CVM-007, one test each, named in the rule's own words. */
class LayerRulesTest {
    private val skeleton =
        mapOf(
            ":app" to listOf(":core", ":plugin-api", ":plugins:status"),
            ":core" to listOf(":plugin-api"),
            ":plugin-api" to listOf(),
            ":plugins" to listOf(),
            ":plugins:status" to listOf(":plugin-api"),
        )

    private fun broken(vararg edge: Pair<String, String>): List<String> {
        val edges = skeleton.mapValues { it.value.toMutableList() }.toMutableMap()
        for ((from, to) in edge) edges.getOrPut(from) { mutableListOf() }.add(to)
        return LayerRules.violations(edges)
    }

    @Test
    fun `the skeleton keeps every rule`() {
        assertEquals(listOf<String>(), LayerRules.violations(skeleton))
    }

    @Test
    fun `the core never depends on a plugin`() {
        assertEquals(
            listOf(":core depends on :plugins:status; the core never depends on a plugin"),
            broken(":core" to ":plugins:status"),
        )
    }

    @Test
    fun `a plugin depends only on the plugin api`() {
        assertEquals(
            listOf(":plugins:status depends on :core; a plugin depends only on :plugin-api and capabilities"),
            broken(":plugins:status" to ":core"),
        )
    }

    @Test
    fun `no plugin depends on another`() {
        assertEquals(
            listOf(":plugins:status depends on :plugins:location; no plugin depends on another"),
            broken(":plugins:location" to ":plugin-api", ":plugins:status" to ":plugins:location"),
        )
    }

    @Test
    fun `the plugin api depends on nothing of ours`() {
        assertEquals(
            listOf(":plugin-api depends on :core; the plugin interface depends on no other module"),
            broken(":plugin-api" to ":core"),
        )
    }

    @Test
    fun `the core depends on nothing but the plugin api`() {
        assertEquals(
            listOf(":core depends on :app; the core depends only on :plugin-api"),
            broken(":core" to ":app"),
        )
    }

    @Test
    fun `a module in no layer is refused`() {
        val found = broken(":extras" to ":plugin-api")
        assertTrue(found.single().startsWith(":extras is in no layer"), found.toString())
    }

    // ADR-CVM-007's capabilities. No capability module exists yet, so these are fixtures here:
    // a capability, an adapter of it, and a plugin that uses it.
    private val withCapability =
        skeleton + mapOf(
            ":app" to listOf(":core", ":plugin-api", ":plugins:status", ":plugins:micmute", ":capabilities:mute:acme"),
            ":capabilities" to listOf(),
            ":capabilities:mute" to listOf(),
            ":capabilities:mute:acme" to listOf(":capabilities:mute"),
            ":plugins:micmute" to listOf(":plugin-api", ":capabilities:mute"),
        )

    private fun brokenWith(vararg edge: Pair<String, String>): List<String> {
        val edges = withCapability.mapValues { it.value.toMutableList() }.toMutableMap()
        for ((from, to) in edge) edges.getOrPut(from) { mutableListOf() }.add(to)
        return LayerRules.violations(edges)
    }

    @Test
    fun `a plugin may use a capability, and an adapter its own capability`() {
        assertEquals(listOf<String>(), LayerRules.violations(withCapability))
    }

    @Test
    fun `a capability depends on no module of ours`() {
        assertEquals(
            listOf(":capabilities:mute depends on :plugin-api; a capability depends on no module of ours"),
            brokenWith(":capabilities:mute" to ":plugin-api"),
        )
    }

    @Test
    fun `an adapter depends only on the capability it adapts`() {
        assertEquals(
            listOf(
                ":capabilities:mute:acme depends on :capabilities:location; " +
                    "an adapter depends only on the capability it adapts",
            ),
            LayerRules.violations(
                withCapability + mapOf(
                    ":capabilities:location" to listOf(),
                    ":capabilities:mute:acme" to listOf(":capabilities:mute", ":capabilities:location"),
                ),
            ),
        )
    }

    @Test
    fun `the core never depends on a capability`() {
        assertEquals(
            listOf(":core depends on :capabilities:mute; the core never depends on a capability"),
            brokenWith(":core" to ":capabilities:mute"),
        )
    }

    @Test
    fun `a plugin wires no adapter, the app does`() {
        assertEquals(
            listOf(":plugins:micmute depends on :capabilities:mute:acme; a plugin never depends on an adapter; the app wires adapters"),
            brokenWith(":plugins:micmute" to ":capabilities:mute:acme"),
        )
    }

    @Test
    fun `the core never depends on an adapter either`() {
        assertEquals(
            listOf(":core depends on :capabilities:mute:acme; the core never depends on a capability"),
            brokenWith(":core" to ":capabilities:mute:acme"),
        )
    }

    @Test
    fun `an adapter depends on neither the plugin interface nor the core`() {
        assertEquals(
            listOf(
                ":capabilities:mute:acme depends on :core; an adapter depends only on the capability it adapts",
                ":capabilities:mute:acme depends on :plugin-api; an adapter depends only on the capability it adapts",
            ),
            brokenWith(":capabilities:mute:acme" to ":plugin-api", ":capabilities:mute:acme" to ":core"),
        )
    }

    // ADR-CVM-007's rules modules: a plugin's decisions in plain Kotlin, at :plugins:<name>:rules.
    // No plugin has one yet, so these are fixtures here.
    private val withRules =
        skeleton + mapOf(
            ":app" to listOf(":core", ":plugin-api", ":plugins:status", ":plugins:sms"),
            ":plugins:sms" to listOf(":plugin-api", ":plugins:sms:rules"),
            ":plugins:sms:rules" to listOf(),
        )

    private fun brokenWithRules(vararg edge: Pair<String, String>): List<String> {
        val edges = withRules.mapValues { it.value.toMutableList() }.toMutableMap()
        for ((from, to) in edge) edges.getOrPut(from) { mutableListOf() }.add(to)
        return LayerRules.violations(edges)
    }

    @Test
    fun `a plugin may depend on its own rules`() {
        assertEquals(listOf<String>(), LayerRules.violations(withRules))
    }

    @Test
    fun `a plugin never depends on another plugin's rules`() {
        assertEquals(
            listOf(":plugins:status depends on :plugins:sms:rules; a plugin depends only on its own rules"),
            brokenWithRules(":plugins:status" to ":plugins:sms:rules"),
        )
    }

    @Test
    fun `a plugin's rules depend on no module of ours`() {
        assertEquals(
            listOf(
                ":plugins:sms:rules depends on :capabilities:mute; a plugin's rules depend only on a capability that holds pure logic",
                ":plugins:sms:rules depends on :core; a plugin's rules depend on no module of ours",
                ":plugins:sms:rules depends on :plugin-api; a plugin's rules depend on no module of ours",
            ),
            brokenWithRules(
                ":plugins:sms:rules" to ":plugin-api",
                ":plugins:sms:rules" to ":core",
                ":plugins:sms:rules" to ":capabilities:mute",
            ).filter { it.startsWith(":plugins:sms:rules") },
        )
    }

    // ADR-CVM-007: a capability that holds only pure logic, such as the shared cursor queue, may
    // be used by a plugin's rules too; one that reaches Android may not.
    private val withPure =
        withRules + mapOf(
            ":capabilities" to listOf(),
            ":capabilities:cursorqueue" to listOf(),
            ":capabilities:contacts" to listOf(),
        )

    private fun brokenWithPure(vararg edge: Pair<String, String>): List<String> {
        val edges = withPure.mapValues { it.value.toMutableList() }.toMutableMap()
        for ((from, to) in edge) edges.getOrPut(from) { mutableListOf() }.add(to)
        return LayerRules.violations(edges, pure = setOf(":capabilities:cursorqueue"))
    }

    @Test
    fun `a plugin's rules may depend on a capability that holds only pure logic`() {
        assertEquals(listOf<String>(), brokenWithPure(":plugins:sms:rules" to ":capabilities:cursorqueue"))
    }

    @Test
    fun `a plugin's rules never depend on a capability that reaches Android`() {
        assertEquals(
            listOf(
                ":plugins:sms:rules depends on :capabilities:contacts; " +
                    "a plugin's rules depend only on a capability that holds pure logic",
            ),
            brokenWithPure(":plugins:sms:rules" to ":capabilities:contacts"),
        )
    }

    @Test
    fun `a pure capability still depends on no module of ours`() {
        assertEquals(
            listOf(":capabilities:cursorqueue depends on :plugin-api; a capability depends on no module of ours"),
            brokenWithPure(":capabilities:cursorqueue" to ":plugin-api"),
        )
    }

    @Test
    fun `neither the core nor the app depends on a plugin's rules`() {
        assertEquals(
            listOf(
                ":app depends on :plugins:sms:rules; the app wires a plugin's entry, never its rules",
                ":core depends on :plugins:sms:rules; the core never depends on a plugin's rules",
            ),
            brokenWithRules(":core" to ":plugins:sms:rules", ":app" to ":plugins:sms:rules"),
        )
    }

    @Test
    fun `a module inside a plugin other than its rules is in no layer`() {
        val found = brokenWithRules(":plugins:sms:extra" to ":plugin-api")
        assertTrue(found.single().startsWith(":plugins:sms:extra is in no layer"), found.toString())
    }
}
