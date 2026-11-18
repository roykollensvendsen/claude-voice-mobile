package org.claudevoice.mobile.core

import org.claudevoice.mobile.pluginapi.Plugin
import org.claudevoice.mobile.pluginapi.Tool
import org.claudevoice.mobile.pluginapi.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PluginsTest {
    private fun plugin(name: String, vararg tools: String) = plugin(name, tools.toList())

    private fun plugin(name: String, tools: List<String>, uses: List<String> = listOf(), confirms: List<String> = listOf()) =
        object : Plugin {
            override val name = name
            override val uses = uses
            override val confirms = confirms
            override val tools = tools.map { Tool(it, "", JsonObject(mapOf())) }

            override suspend fun call(tool: String, arguments: JsonObject) = ToolResult.Answer(JsonObject(mapOf()))
        }

    @Test
    fun `a tool is offered under its plugin's name`() {
        val offered = Plugins(listOf(plugin("status", "status_battery"))).offered()
        assertEquals(listOf("status_battery"), offered)
    }

    @Test
    fun `two plugins may not offer the same tool`() {
        assertThrows<IllegalArgumentException> {
            Plugins(listOf(plugin("status", "status_ring"), plugin("other", "status_ring")))
        }
    }

    @Test
    fun `a tool not named after its plugin is refused`() {
        assertThrows<IllegalArgumentException> { Plugins(listOf(plugin("status", "ring"))) }
    }

    @Test
    fun `a plugin's name follows the protocol's rule`() {
        for (bad in listOf("Status", "1status", "sta-tus", "", "s".repeat(33))) {
            assertThrows<IllegalArgumentException>(bad) { Plugins(listOf(plugin(bad, "${bad}_x"))) }
        }
        Plugins(listOf(plugin("s".repeat(32), "${"s".repeat(32)}_x"), plugin("earbuds_2", "earbuds_2_mode")))
    }

    @Test
    fun `a tool's own part is lowercase letters, digits and underscores, and not empty`() {
        for (bad in listOf("status_", "status_Ring", "status_ring-now", "status_ring now")) {
            assertThrows<IllegalArgumentException>(bad) { Plugins(listOf(plugin("status", bad))) }
        }
    }

    @Test
    fun `two plugins may not share a name`() {
        assertThrows<IllegalArgumentException> {
            Plugins(listOf(plugin("status", "status_ring"), plugin("status", "status_battery")))
        }
    }

    @Test
    fun `no tool may take a setting only the phone changes as an input`() {
        for (setting in listOf("microphone", "location_log", "direct_calls", "camera")) {
            val input = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") { putJsonObject(setting) { put("type", "string") } }
            }
            val sneaky =
                object : Plugin {
                    override val name = "status"
                    override val uses = listOf<String>()
                    override val confirms = listOf<String>()
                    override val tools = listOf(Tool("status_set", "", input))

                    override suspend fun call(tool: String, arguments: JsonObject) = ToolResult.Answer(JsonObject(mapOf()))
                }
            assertThrows<IllegalArgumentException>(setting) { Plugins(listOf(sneaky)) }
        }
    }

    @Test
    fun `a setting is caught under another spelling or deeper in the input`() {
        val camel = buildJsonObject {
            putJsonObject("properties") { putJsonObject("locationLog") { put("type", "boolean") } }
        }
        val nested = buildJsonObject {
            putJsonObject("properties") {
                putJsonObject("options") { putJsonObject("properties") { putJsonObject("Direct-Calls") { put("type", "array") } } }
            }
        }
        for (input in listOf(camel, nested)) {
            val sneaky =
                object : Plugin {
                    override val name = "status"
                    override val uses = listOf<String>()
                    override val confirms = listOf<String>()
                    override val tools = listOf(Tool("status_set", "", input))

                    override suspend fun call(tool: String, arguments: JsonObject) = ToolResult.Answer(JsonObject(mapOf()))
                }
            assertThrows<IllegalArgumentException>(input.toString()) { Plugins(listOf(sneaky)) }
        }
    }

    @Test
    fun `no plugin may offer agent_info, which the core answers for the whole phone`() {
        // A plugin named "agent" with a tool "info" would otherwise take the name.
        assertThrows<IllegalArgumentException> { Plugins(listOf(plugin("agent", "agent_info"))) }
    }

    @Test
    fun `a confirming tool must be one of the plugin's own tools`() {
        assertThrows<IllegalArgumentException> {
            Plugins(listOf(plugin("phone", listOf("phone_call"), confirms = listOf("phone_confirm"))))
        }
        assertThrows<IllegalArgumentException> {
            Plugins(listOf(plugin("phone", listOf("phone_call"), confirms = listOf("phone_call", "phone_call"))))
        }
        Plugins(listOf(plugin("phone", listOf("phone_call", "phone_confirm"), confirms = listOf("phone_confirm"))))
    }

    @Test
    fun `what a plugin uses is one of the names the protocol lists, once each`() {
        val bads = listOf(listOf("Microphone"), listOf("micropone"), listOf("a_later_thing"), listOf(""), listOf("calls", "calls"))
        for (bad in bads) {
            assertThrows<IllegalArgumentException>(bad.toString()) {
                Plugins(listOf(plugin("phone", listOf("phone_call"), uses = bad)))
            }
        }
        val nine = listOf("microphone", "camera", "location", "calls", "contacts", "notifications", "media", "sensors", "usage")
        Plugins(listOf(plugin("phone", listOf("phone_call"), uses = nine)))
        // The next version's name for text messages, which the sms plugins need (ADR-CVM-010).
        Plugins(listOf(plugin("sms", listOf("sms_since"), uses = listOf("messages"))))
    }

    @Test
    fun `a name a plugin starts to use while running is checked again when the hub asks`() {
        val reach = mutableListOf<String>()
        val phone = object : Plugin {
            override val name = "phone"
            override val uses get() = reach.toList()
            override val confirms = listOf<String>()
            override val tools = listOf(Tool("phone_call", "", JsonObject(mapOf())))

            override suspend fun call(tool: String, arguments: JsonObject) = ToolResult.Answer(JsonObject(mapOf()))
        }
        val plugins = Plugins(listOf(phone))
        reach.add("micropone")
        assertThrows<IllegalStateException> { plugins.entries() }
    }

    @Test
    fun `each plugin's entry for agent_info names its tools, what confirms a yes, and what it uses`() {
        val plugins = Plugins(
            listOf(
                plugin("status", "status_battery", "status_ring"),
                plugin("phone", listOf("phone_call", "phone_confirm"), listOf("calls", "contacts"), listOf("phone_confirm")),
            ),
        )
        assertEquals(
            """[{"name":"status","tools":["status_battery","status_ring"],"confirms":[],"uses":[]},""" +
                """{"name":"phone","tools":["phone_call","phone_confirm"],"confirms":["phone_confirm"],"uses":["calls","contacts"]}]""",
            plugins.entries().toString(),
        )
    }

    @Test
    fun `an entry reads what a plugin uses at the time it is asked`() {
        val reach = mutableListOf<String>()
        val phone = object : Plugin {
            override val name = "phone"
            override val uses get() = reach.toList()
            override val confirms = listOf<String>()
            override val tools = listOf(Tool("phone_call", "", JsonObject(mapOf())))

            override suspend fun call(tool: String, arguments: JsonObject) = ToolResult.Answer(JsonObject(mapOf()))
        }
        val plugins = Plugins(listOf(phone))
        reach.add("calls")
        assertEquals("""["calls"]""", plugins.entries().single().jsonObject["uses"].toString())
    }
}
