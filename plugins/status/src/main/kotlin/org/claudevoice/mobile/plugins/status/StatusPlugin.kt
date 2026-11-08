package org.claudevoice.mobile.plugins.status

import org.claudevoice.mobile.pluginapi.Plugin
import org.claudevoice.mobile.pluginapi.Tool
import org.claudevoice.mobile.pluginapi.ToolResult
import kotlinx.serialization.json.JsonObject

/** The first plugin, empty until the protocol it answers in is frozen. */
class StatusPlugin : Plugin {
    override val name = "status"
    override val tools = listOf<Tool>()

    override suspend fun call(tool: String, arguments: JsonObject): ToolResult = ToolResult.Refused("status has no tool $tool")
}
