package org.claudevoice.mobile.plugins.sms

import kotlinx.serialization.json.JsonObject
import org.claudevoice.mobile.pluginapi.Plugin
import org.claudevoice.mobile.pluginapi.Tool
import org.claudevoice.mobile.pluginapi.ToolResult
import org.claudevoice.mobile.plugins.sms.rules.Inbox
import org.claudevoice.mobile.plugins.sms.rules.SmsSince

/**
 * Reads the owner's new text messages for the hub (ADR-CVM-010), through `sms_since`. Everything
 * it decides is in its rules module. With no [inbox] it offers nothing and uses nothing: the
 * receiver and the Keystore key that make one come in the next step, and until then [Entry]
 * builds it without.
 */
class SmsPlugin(private val inbox: Inbox? = null) : Plugin {
    override val name = "sms"
    override val tools =
        if (inbox == null) listOf() else listOf(Tool(SmsSince.TOOL, SmsSince.DESCRIPTION, SmsSince.INPUT))
    override val uses = if (inbox == null) listOf() else listOf("messages")
    override val confirms = listOf<String>()

    override suspend fun call(tool: String, arguments: JsonObject): ToolResult {
        val held = inbox
        if (held == null || tool != SmsSince.TOOL) return ToolResult.Refused("sms has no tool $tool")
        return when (val reply = SmsSince.call(held, arguments)) {
            is SmsSince.Reply.Answer -> ToolResult.Answer(reply.content)
            is SmsSince.Reply.Refused -> ToolResult.Refused(reply.say)
        }
    }
}
