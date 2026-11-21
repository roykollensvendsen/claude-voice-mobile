package org.claudevoice.mobile.plugins.sms_send.rules

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CountriesTest {
    @Test
    fun `the SIM's country comes first, so a saved number is never given another country's code`() {
        assertEquals("NO", Countries.chosen(sim = "NO", locale = "SE"))
        assertEquals("NO", Countries.chosen(sim = "no", locale = "se"))
    }

    @Test
    fun `a blank SIM falls back to the phone's own region setting`() {
        assertEquals("SE", Countries.chosen(sim = "", locale = "se"))
        assertEquals("SE", Countries.chosen(sim = " ", locale = "SE"))
        assertEquals("SE", Countries.chosen(sim = null, locale = "SE"))
    }

    @Test
    fun `with no country anywhere there is none, and a number without one stays as it was saved`() {
        assertNull(Countries.chosen(sim = null, locale = " "))
        assertNull(Countries.chosen(sim = "", locale = null))
    }

    @Test
    fun `a country is in capitals, with no spaces around`() {
        assertEquals("NO", Countries.chosen(sim = " no ", locale = null))
        assertEquals("SE", Countries.chosen(sim = null, locale = " se "))
    }
}
