package org.claudevoice.mobile.app

import kotlinx.serialization.json.JsonObject
import org.claudevoice.mobile.pluginapi.Plugin
import org.claudevoice.mobile.pluginapi.Tool
import org.claudevoice.mobile.pluginapi.ToolResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class BuiltPluginsTest {
    private fun plugin(called: String) = object : Plugin {
        override val name = called
        override val tools = listOf<Tool>()
        override val uses = listOf<String>()
        override val confirms = listOf<String>()

        override suspend fun call(tool: String, arguments: JsonObject) = ToolResult.Refused("")
    }

    @Test
    fun a_plugin_answers_by_its_folder_s_name() {
        val status = plugin("status")
        assertSame(status, BuiltPlugin.named("status", status))
    }

    @Test
    fun a_plugin_whose_name_is_not_its_folder_s_is_refused_when_the_app_starts() {
        val refused = assertThrows(IllegalStateException::class.java) { BuiltPlugin.named("sms_send", plugin("smssend")) }
        assertEquals(
            "the plugin in plugins/sms_send calls itself smssend; its folder, package and name are one string",
            refused.message,
        )
    }
}
