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
}
