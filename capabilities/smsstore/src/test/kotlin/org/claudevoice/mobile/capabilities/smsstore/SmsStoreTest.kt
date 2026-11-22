package org.claudevoice.mobile.capabilities.smsstore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmsStoreTest {
    // Made-up numbers in the +47 00 range no one is given.
    private var reads = 0

    /** Reads a local number as Norwegian, as the phone would with a Norwegian SIM. */
    private val norwegian = { raw: String -> if (raw.length == 8) "+47$raw" else null }

    private fun store(permitted: Boolean, vararg sent: String?) = SmsStore(
        { permitted },
        {
            reads += 1
            sent.asSequence()
        },
        norwegian,
    )

    @Test
    fun `says a number was texted before when a sent message went to it`() {
        assertEquals(true, store(true, "+4700000099", "+4700000001").sentBefore("+4700000001"))
    }

    @Test
    fun `says a number was never texted when no sent message went to it`() {
        assertEquals(false, store(true, "+4700000099").sentBefore("+4700000001"))
    }

    @Test
    fun `a sent address written without its code, or with gaps, still matches`() {
        assertEquals(true, store(true, "00000001").sentBefore("+4700000001"))
        assertEquals(true, store(true, "+47 000 00 001").sentBefore("+4700000001"))
    }

    @Test
    fun `an empty sent box, or addresses that are no number, say never texted`() {
        assertEquals(false, store(true).sentBefore("+4700000001"))
        assertEquals(false, store(true, null, "", "Bank").sentBefore("+4700000001"))
    }

    @Test
    fun `says nothing, and reads nothing, when the app may not read texts`() {
        assertNull(store(false, "+4700000001").sentBefore("+4700000001"))
        assertEquals(0, reads)
    }

    @Test
    fun `an address is read in full international form`() {
        assertEquals("+4700000001", SmsStore.international("+47 (000) 00-001", norwegian))
        assertEquals("+4700000001", SmsStore.international("00000001", norwegian))
        assertNull(SmsStore.international(" ", norwegian))
        assertNull(SmsStore.international("Bank", norwegian))
    }
}
