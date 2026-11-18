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
    fun `an empty list fails the build rather than build an app with no plugin`() {
        val refused = assertThrows<IllegalArgumentException> { ModulePicks.picked(" , ", known) }
        assertTrue(refused.message!!.startsWith("cvPlugins names no plugin"), refused.message)
    }
}
