package org.claudevoice.mobile.core

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.claudevoice.mobile.pluginapi.Microphone
import org.claudevoice.mobile.pluginapi.Plugin
import org.claudevoice.mobile.pluginapi.Tool
import org.claudevoice.mobile.pluginapi.ToolResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AgentInfoTest {
    private val status = object : Plugin {
        override val name = "status"
        override val uses = listOf<String>()
        override val confirms = listOf<String>()
        override val tools = listOf("battery", "ring").map { Tool("status_$it", "", JsonObject(mapOf())) }

        override suspend fun call(tool: String, arguments: JsonObject) = ToolResult.Answer(JsonObject(mapOf()))
    }

    private var hub: String? = null
    private val keeper = SettingsKeeper()

    private val place = object : AgentPlace {
        override fun address() = "100.64.0.10"

        override fun hub() = hub
    }

    private val info = AgentInfo(Plugins(listOf(status)), "0.1.0", "phone-a", keeper.settings, place, GrantedAll)

    @Test
    fun `the answer holds every field protocol version 1 asks for, and no other`() {
        val report = info.report()
        assertEquals(
            listOf("protocol", "machine", "address", "hub", "tools", "version", "plugins", "privacy"),
            report.keys.toList(),
        )
        assertEquals(1, report["protocol"]!!.jsonPrimitive.int)
        assertEquals("phone-a", report["machine"]!!.jsonPrimitive.content)
        assertEquals("100.64.0.10", report["address"]!!.jsonPrimitive.content)
        assertEquals("0.1.0", report["version"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the tools are every plugin's, without agent_info itself`() {
        assertEquals(listOf("status_battery", "status_ring"), info.report()["tools"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(Plugins(listOf(status)).entries(GrantedAll), info.report()["plugins"])
    }

    @Test
    fun `the hub is null before pairing, and its name after`() {
        assertEquals(JsonNull, info.report()["hub"])
        hub = "laptop-a"
        assertEquals("laptop-a", info.report()["hub"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the privacy settings are read as they are when the hub asks`() {
        assertEquals("""{"microphone":"none","location_log":false}""", info.report()["privacy"].toString())
        keeper.setMicrophone(Microphone.AFTER_WARNING)
        keeper.setLocationLog(true)
        assertEquals("""{"microphone":"after-warning","location_log":true}""", info.report()["privacy"].toString())
    }

    @Test
    fun `the phone's name follows the protocol's rule for a machine name`() {
        for (bad in listOf("Phone-a", "1phone", "phone_a", "", "p".repeat(33), "phone a")) {
            org.junit.jupiter.api.assertThrows<IllegalArgumentException>(bad) {
                AgentInfo(Plugins(listOf(status)), "0.1.0", bad, keeper.settings, place, GrantedAll)
            }
        }
        AgentInfo(Plugins(listOf(status)), "0.1.0", "p".repeat(32), keeper.settings, place, GrantedAll)
        AgentInfo(Plugins(listOf(status)), "0.1.0", "phone-2", keeper.settings, place, GrantedAll)
    }
}

class SuggestedMachineTest {
    @org.junit.jupiter.api.Test
    fun `a phone model becomes a name the protocol takes`() {
        val cases = mapOf(
            "Pixel 8 Pro" to "pixel-8-pro",
            "SM-S921B" to "sm-s921b",
            "  Galaxy  S24 (Ultra) " to "galaxy-s24-ultra",
            "9T" to "t",
            "ZTE Blade" to "zte-blade",
            "Ünïcode 5" to "n-code-5",
            "a".repeat(40) to "a".repeat(32),
            "abc-" + "d".repeat(27) + "-x" to "abc-" + "d".repeat(27),
        )
        for ((model, name) in cases) {
            org.junit.jupiter.api.Assertions.assertEquals(name, suggestedMachine(model), model)
        }
    }

    @org.junit.jupiter.api.Test
    fun `a model with nothing usable is just phone`() {
        for (model in listOf("", "   ", "123", "---", "中文")) {
            org.junit.jupiter.api.Assertions.assertEquals("phone", suggestedMachine(model), model)
        }
    }
}
