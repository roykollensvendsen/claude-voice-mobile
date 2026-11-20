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
    override val tools = listOfNotNull(inbox?.let { Tool(SmsSince.TOOL, SmsSince.DESCRIPTION, SmsSince.INPUT) })
    override val uses = listOfNotNull(inbox?.let { "messages" })
    override val confirms = listOf<String>()

    override suspend fun call(tool: String, arguments: JsonObject): ToolResult =
        SmsSince.call(inbox, tool, arguments).fold({ ToolResult.Answer(it) }, { ToolResult.Refused(it) })
}
