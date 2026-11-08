package org.claudevoice.mobile.core

import org.claudevoice.mobile.pluginapi.Plugin
import org.claudevoice.mobile.pluginapi.Tool
import org.claudevoice.mobile.pluginapi.ToolResult
import kotlinx.serialization.json.JsonObject
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
}
