package org.claudevoice.mobile.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** The hub's name as the laptop's own agent gives it: the first label of its tailnet name. */
class HubNameTest {
    @Test
    fun `a MagicDNS name is cut to its first label`() {
        assertEquals("laptop-a", hubName("laptop-a.tail1234.ts.net", "100.64.0.1"))
        assertEquals("laptop-a", hubName("laptop-a.tail1234.ts.net.", "100.64.0.1"))
        assertEquals("laptop-a", hubName("laptop-a", "100.64.0.1"))
    }

    @Test
    fun `a lookup that only gives the address back, or nothing, is no name`() {
        for (looked in listOf("100.64.0.1", "", ".", ".tail1234.ts.net")) {
            assertNull(hubName(looked, "100.64.0.1"), looked)
        }
    }
}
