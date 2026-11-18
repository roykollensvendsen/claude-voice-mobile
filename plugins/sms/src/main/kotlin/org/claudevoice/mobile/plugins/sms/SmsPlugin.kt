package org.claudevoice.mobile.plugins.sms

import kotlinx.serialization.json.JsonObject
import org.claudevoice.mobile.pluginapi.Plugin
import org.claudevoice.mobile.pluginapi.Tool
import org.claudevoice.mobile.pluginapi.ToolResult

/**
 * Reads the owner's new text messages for the hub (ADR-CVM-010). So far it holds only the rule
 * that hides one-time codes, in its rules module; it receives nothing and offers no tool. It
 * says it uses `messages` only once it reads them, with the receiver that does.
 */
class SmsPlugin : Plugin {
    override val name = "sms"
    override val tools = listOf<Tool>()
    override val uses = listOf<String>()
    override val confirms = listOf<String>()

    override suspend fun call(tool: String, arguments: JsonObject): ToolResult =
        ToolResult.Refused("sms has no tool $tool")
}
