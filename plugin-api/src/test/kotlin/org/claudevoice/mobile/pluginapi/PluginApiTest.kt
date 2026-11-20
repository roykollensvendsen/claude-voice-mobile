package org.claudevoice.mobile.pluginapi

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.assertThrows
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
    fun `a refusal has no code unless the plugin gives one`() {
        assertNull(ToolResult.Refused("The earbuds are not connected.").code)
        val missing = ToolResult.Refused("Allow text messages in the phone's settings.", "permission_missing")
        assertEquals("permission_missing", missing.code)
    }

    @Test
    fun `a refusal with no words is not made, with a code or without`() {
        // claude-voice, docs/agent-protocol/v1/error.json: error has minLength 1.
        for (say in listOf("", " ", "\n\t")) {
            assertThrows<IllegalArgumentException>(say) { ToolResult.Refused(say) }
            assertThrows<IllegalArgumentException>(say) { ToolResult.Refused(say, "permission_missing") }
        }
    }

    @Test
    fun `a code is what protocol version 1's error file allows, or the refusal is not made`() {
        // claude-voice, docs/agent-protocol/v1/error.json: ^[a-z][a-z0-9_]{0,31}$
        for (code in listOf("a", "permission_missing", "x9_", "a" + "b".repeat(31))) {
            assertEquals(code, ToolResult.Refused("No.", code).code)
        }
        for (code in listOf("", "Permission", "9a", "_a", "a-b", "a b", "a" + "b".repeat(32), "a\n")) {
            assertThrows<IllegalArgumentException>(code) { ToolResult.Refused("No.", code) }
        }
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
        assertNotEquals(tool, Tool("status", "How the battery is", input))
    }
}
