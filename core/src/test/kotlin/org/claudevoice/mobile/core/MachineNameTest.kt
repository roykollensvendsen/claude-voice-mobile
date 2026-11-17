package org.claudevoice.mobile.core

import java.io.IOException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** The name the phone gives itself, confirmed by the owner before the first pairing. */
class MachineNameTest {
    private class Memory : TextStore {
        var text: String? = null
        var failing = false

        override fun read() = text

        override fun write(text: String) {
            if (failing) throw IOException("disk full")
            this.text = text
        }
    }

    private val store = Memory()
    private var paired = false
    private fun names() = MachineName(store, paired = { paired })

    @Test
    fun `before the owner confirms one, there is no name`() {
        assertNull(names().name)
    }

    @Test
    fun `a confirmed name is kept with its version, and read back after a restart`() {
        assertEquals(Confirmed.Kept, names().confirm("pixel-8"))
        assertEquals("""{"version":1,"name":"pixel-8"}""", store.text)
        assertEquals("pixel-8", names().name)
    }

    @Test
    fun `a name the protocol does not take is refused, and nothing is kept`() {
        for (bad in listOf("", "Pixel", "8pro", "a b", "a".repeat(33), "pixel_8")) {
            assertEquals(Confirmed.NotAllowed, names().confirm(bad), bad)
        }
        assertNull(store.text)
        assertEquals(Confirmed.Kept, names().confirm("a".repeat(32)))
    }

    @Test
    fun `once paired, a different name is refused, since changing it means pairing again`() {
        names().confirm("pixel-8")
        paired = true
        assertEquals(Confirmed.PairedAs, names().confirm("pixel-9"))
        assertEquals("pixel-8", names().name)
        assertEquals(Confirmed.Kept, names().confirm("pixel-8"))
    }

    @Test
    fun `a name that cannot be written is not kept, and says so`() {
        store.failing = true
        val machine = names()
        assertEquals(Confirmed.NotWritten, machine.confirm("pixel-8"))
        assertNull(machine.name)
    }

    @Test
    fun `a kept file that cannot be read, or holds a name the protocol does not take, is no name`() {
        for (text in listOf("not json", "{}", """{"version":1,"name":"Bad Name"}""", """{"version":2,"name":"pixel-8"}""", "[]")) {
            store.text = text
            assertNull(names().name, text)
        }
    }

    @Test
    fun `nothing is built before a name is confirmed, then one thing, once, with that name`() {
        var name: String? = null
        val builds = mutableListOf<String>()
        val once = OnceNamed({ name }) { it.also { builds += it } }
        assertNull(once.get())
        name = "pixel-8"
        assertEquals("pixel-8", once.get())
        name = "pixel-9"
        assertEquals("pixel-8", once.get())
        assertEquals(listOf("pixel-8"), builds)
    }
}
