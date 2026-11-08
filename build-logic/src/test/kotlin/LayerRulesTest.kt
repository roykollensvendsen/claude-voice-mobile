import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The layer rules of ADR-CVM-003, one test each, named in the rule's own words. */
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
            listOf(":plugins:status depends on :core; a plugin depends only on :plugin-api"),
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
}
