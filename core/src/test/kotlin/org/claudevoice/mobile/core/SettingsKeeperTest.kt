package org.claudevoice.mobile.core

import org.claudevoice.mobile.pluginapi.Microphone
import org.claudevoice.mobile.pluginapi.PhoneSettings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SettingsKeeperTest {
    @Test
    fun `everything starts at its most private`() {
        val settings = SettingsKeeper().settings
        assertEquals(Microphone.NONE, settings.microphone)
        assertFalse(settings.locationLog)
        assertEquals(emptyList<String>(), settings.directCalls)
        assertFalse(settings.camera)
    }

    @Test
    fun `what the phone's own screens change, a plugin reads`() {
        val keeper = SettingsKeeper()
        val seen: PhoneSettings = keeper.settings
        keeper.setMicrophone(Microphone.MUST_ANSWER)
        keeper.setLocationLog(true)
        keeper.setDirectCalls(listOf("+4700000000"))
        keeper.setCamera(true)
        assertEquals(Microphone.MUST_ANSWER, seen.microphone)
        assertTrue(seen.locationLog)
        assertEquals(listOf("+4700000000"), seen.directCalls)
        assertTrue(seen.camera)
    }

    @Test
    fun `what a plugin is handed cannot be turned into the writer`() {
        val keeper = SettingsKeeper()
        val seen: Any = keeper.settings
        assertFalse(seen is SettingsKeeper)
        val setters = seen.javaClass.methods.filter { it.name.startsWith("set") }
        assertEquals(emptyList<String>(), setters.map { it.name })
    }

    @Test
    fun `a plugin cannot change the direct-call list through the list it reads`() {
        val keeper = SettingsKeeper()
        keeper.setDirectCalls(listOf("+4700000000"))
        val list = keeper.settings.directCalls
        assertFalse(list is MutableList<*> && runCatching { (list as MutableList<String>).add("x") }.isSuccess)
        assertEquals(listOf("+4700000000"), keeper.settings.directCalls)
    }

    @Test
    fun `the microphone setting is named on the wire as protocol version 1 names it`() {
        assertEquals(listOf("none", "must-answer", "after-warning"), Microphone.entries.map { it.wire })
    }

    @Test
    fun `the report to the hub carries the microphone setting and location logging`() {
        val keeper = SettingsKeeper()
        keeper.setMicrophone(Microphone.AFTER_WARNING)
        assertEquals(
            """{"microphone":"after-warning","location_log":false}""",
            privacyReport(keeper.settings).toString(),
        )
    }
}
