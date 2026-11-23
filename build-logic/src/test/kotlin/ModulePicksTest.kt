import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** ADR-CVM-007: the modules a build takes are picked by one property, -PcvPlugins. */
class ModulePicksTest {
    private val known = setOf("status", "sms", "micmute")

    @Test
    fun `with no list given, the build takes status alone`() {
        assertEquals(listOf("status"), ModulePicks.picked(null, known))
    }

    @Test
    fun `the build takes the plugins the list names, in its order, once each`() {
        assertEquals(listOf("sms", "status"), ModulePicks.picked(" sms, status ,sms", known))
    }

    @Test
    fun `a name that is not a module fails the build, with the names it knows`() {
        val refused = assertThrows<IllegalArgumentException> { ModulePicks.picked("status,smss", known) }
        assertEquals("cvPlugins names smss, which is not a module; the modules are: micmute, sms, status", refused.message)
    }

    @Test
    fun `a name the protocol would refuse fails the build, even when its folder exists`() {
        val refused = assertThrows<IllegalArgumentException> { ModulePicks.picked("status,Sms-2", known + "Sms-2") }
        assertEquals(
            "cvPlugins names Sms-2, which is not a plugin name: a small letter, then at most 31 small letters, digits or _",
            refused.message,
        )
    }

    @Test
    fun `a name of 32 characters is taken, and one of 33 is refused`() {
        val longest = "a" + "b".repeat(31)
        assertEquals(listOf(longest), ModulePicks.picked(longest, setOf(longest, longest + "c")))
        val refused = assertThrows<IllegalArgumentException> { ModulePicks.picked(longest + "c", setOf(longest, longest + "c")) }
        assertTrue(refused.message!!.startsWith("cvPlugins names ${longest}c, which is not a plugin name"), refused.message)
    }

    @Test
    fun `all takes every plugin folder, so a check on it leaves none out`() {
        assertEquals(listOf("micmute", "sms", "status"), ModulePicks.picked(" all ", known))
    }

    @Test
    fun `all refuses a folder whose name the protocol would refuse, as naming it would`() {
        val refused = assertThrows<IllegalArgumentException> { ModulePicks.picked("all", known + "Sms-2") }
        assertTrue(refused.message!!.startsWith("cvPlugins names Sms-2, which is not a plugin name"), refused.message)
    }

    @Test
    fun `a plugin folder named all is refused, since all means every plugin`() {
        val refused = assertThrows<IllegalArgumentException> { ModulePicks.picked("status", known + "all") }
        assertEquals("a plugin folder is named all, the word for every plugin; rename the folder", refused.message)
    }

    @Test
    fun `an empty list fails the build rather than build an app with no plugin`() {
        val refused = assertThrows<IllegalArgumentException> { ModulePicks.picked(" , ", known) }
        assertTrue(refused.message!!.startsWith("cvPlugins names no plugin"), refused.message)
    }

    @Test
    fun `a picked plugin brings its rules along, and an unpicked one does not`() {
        assertEquals(
            listOf(":plugins:sms", ":plugins:sms:rules", ":plugins:status"),
            ModulePicks.included(listOf("sms", "status"), hasRules = { it == "sms" || it == "micmute" }),
        )
    }

    @Test
    fun `a picked plugin brings the capabilities it uses, each once, and an unpicked one brings none`() {
        val uses = mapOf("sms_send" to listOf("contacts"), "sms" to listOf("contacts", "smsstore"), "call" to listOf("mute"))
        assertEquals(
            listOf(":plugins:sms", ":plugins:sms_send", ":capabilities:contacts", ":capabilities:smsstore"),
            ModulePicks.included(listOf("sms", "sms_send"), hasRules = { false }, uses = { uses[it].orEmpty() }),
        )
    }

    @Test
    fun `the capabilities a plugin uses are the ones its build file depends on, never an adapter`() {
        val build = """
            dependencies {
                implementation(project(":plugin-api"))
                implementation(project(":capabilities:contacts"))
                implementation(project(":plugins:sms_send:rules"))
                implementation(project( ":capabilities:smsstore" ))
                implementation(project(":capabilities:mute:jabra"))
            }
        """.trimIndent()
        assertEquals(listOf("contacts", "smsstore"), ModulePicks.capabilities(build))
        assertEquals(emptyList<String>(), ModulePicks.capabilities("dependencies { }"))
    }

    @Test
    fun `a plugin uses the capabilities its own build file and its rules' build file depend on, each once`() {
        val plugin = """implementation(project(":capabilities:contacts"))"""
        val rules = """implementation(project(":capabilities:cursorqueue"))
            implementation(project(":capabilities:contacts"))"""
        assertEquals(listOf("contacts", "cursorqueue"), ModulePicks.uses(plugin, rules))
        assertEquals(listOf("contacts"), ModulePicks.uses(plugin, null))
    }
}
