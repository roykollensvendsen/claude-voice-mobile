package org.claudevoice.mobile.pluginapi

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class PluginApiTest {
    @Test
    fun `the microphone setting has exactly the three names protocol version 1 gives it`() {
        // claude-voice, docs/agent-protocol.md: `none`, `must-answer` or `after-warning`.
        assertEquals(listOf("none", "must-answer", "after-warning"), Microphone.entries.map { it.wire })
    }

    @Test
    fun `a refusal carries the words a person can act on`() {
        val refused: ToolResult = ToolResult.Refused("The earbuds are not connected.")
        assertEquals("The earbuds are not connected.", (refused as ToolResult.Refused).say)
    }

    @Test
    fun `a tool shows the hub its name and description as given`() {
        val tool = Tool("status", "How the phone is", JsonObject(emptyMap()))
        assertEquals("status", tool.name)
        assertEquals("How the phone is", tool.description)
    }

    @Test
    fun `two tools are the same only when name, description and input all match`() {
        val input = JsonObject(mapOf("type" to JsonPrimitive("object")))
        val tool = Tool("status", "How the phone is", input)
        assertEquals(tool, Tool("status", "How the phone is", input))
        assertNotEquals(tool, Tool("status", "How the phone is", JsonObject(emptyMap())))
        assertNotEquals(tool, Tool("battery", "How the phone is", input))
    }
}
