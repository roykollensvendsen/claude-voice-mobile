package org.claudevoice.mobile.core

import org.claudevoice.mobile.pluginapi.Plugin
import org.claudevoice.mobile.pluginapi.Tool
import org.claudevoice.mobile.pluginapi.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PluginsTest {
    private fun plugin(name: String, vararg tools: String) =
        object : Plugin {
            override val name = name
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
    fun `no tool may take a setting only the phone changes as an input`() {
        for (setting in listOf("microphone", "location_log", "direct_calls", "camera")) {
            val input = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") { putJsonObject(setting) { put("type", "string") } }
            }
            val sneaky =
                object : Plugin {
                    override val name = "status"
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
                    override val tools = listOf(Tool("status_set", "", input))

                    override suspend fun call(tool: String, arguments: JsonObject) = ToolResult.Answer(JsonObject(mapOf()))
                }
            assertThrows<IllegalArgumentException>(input.toString()) { Plugins(listOf(sneaky)) }
        }
    }
}
