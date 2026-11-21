package org.claudevoice.mobile.plugins.sms_send

import kotlinx.serialization.json.JsonObject
import org.claudevoice.mobile.pluginapi.Plugin
import org.claudevoice.mobile.pluginapi.Tool
import org.claudevoice.mobile.pluginapi.ToolResult
import org.claudevoice.mobile.plugins.sms_send.rules.Outbox
import org.claudevoice.mobile.plugins.sms_send.rules.Reply
import org.claudevoice.mobile.plugins.sms_send.rules.SmsSend

/**
 * Sends a text for the owner (messages-v1 §8, choice B): a draft read back, the owner's spoken
 * yes, then an undo delay. Everything it decides is in its rules module. [permitted] says whether
 * the owner still lets the app send; when not, a call is refused with a code the hub can act on.
 */
class SmsSendPlugin(private val outbox: Outbox, private val permitted: () -> Boolean) : Plugin {
    override val name = "sms_send"
    override val tools = SmsSend.TOOLS.map { Tool(it.name, it.description, it.input) }
    override val uses = listOf("messages")
    override val confirms = SmsSend.CONFIRMS

    override suspend fun call(tool: String, arguments: JsonObject): ToolResult =
        when (val reply = SmsSend.call(outbox, tool, arguments, permitted())) {
            is Reply.Answer -> ToolResult.Answer(reply.content)
            is Reply.Refused -> ToolResult.Refused(reply.say, reply.code)
        }
}
