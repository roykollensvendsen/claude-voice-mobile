package org.claudevoice.mobile.plugins.sms

import kotlinx.serialization.json.JsonObject
import org.claudevoice.mobile.pluginapi.Plugin
import org.claudevoice.mobile.pluginapi.Tool
import org.claudevoice.mobile.pluginapi.ToolResult
import org.claudevoice.mobile.plugins.sms.rules.Inbox
import org.claudevoice.mobile.plugins.sms.rules.SmsSince

/**
 * Reads the owner's new text messages for the hub (ADR-CVM-010), through `sms_since`. Everything
 * it decides is in its rules module. With no [inbox] it offers nothing and uses nothing. The
 * inbox is opened on the first call, never while the app builds its plugins, since opening reads
 * a file and asks the Keystore.
 * [permitted] says whether the owner still lets the app receive and read messages; when not, a
 * call is refused with a code the hub can act on.
 */
class SmsPlugin(private val inbox: (() -> Inbox)? = null, private val permitted: () -> Boolean = { true }) : Plugin {
    override val name = "sms"
    override val tools = listOfNotNull(inbox?.let { Tool(SmsSince.TOOL, SmsSince.DESCRIPTION, SmsSince.INPUT) })
    override val uses = listOfNotNull(inbox?.let { "messages" })
    override val needs = mapOf("messages" to listOf("android.permission.RECEIVE_SMS", "android.permission.READ_SMS"))
    override val confirms = listOf<String>()

    override suspend fun call(tool: String, arguments: JsonObject): ToolResult =
        SmsSince.call(inbox?.invoke(), tool, arguments, permitted())
            .fold({ ToolResult.Answer(it) }, { say, code -> ToolResult.Refused(say, code) })
}
