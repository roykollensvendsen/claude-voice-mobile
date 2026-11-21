package org.claudevoice.mobile.plugins.sms_send.rules

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/** A tool as the plugin offers it; the plugin turns it into the interface's own type. */
data class ToolSpec(val name: String, val description: String, val input: JsonObject) {
    /** The arguments the tool takes, every one of them required. */
    val keys: Set<String> get() = input.getValue("properties").jsonObject.keys
}

/**
 * The three sending tools (messages-v1 §8, choice B): their names, inputs and how a call is read.
 * The names are fixed with the hub, whose deny rule matches `sms_send_confirm`: none of the three
 * is marked read-only, and only confirm needs the owner's spoken yes.
 */
object SmsSend {
    const val REQUEST = "sms_send_request"
    const val CONFIRM = "sms_send_confirm"
    const val CANCEL = "sms_send_cancel"

    /** Protocol version 1's code for a refusal the owner can lift by granting a permission. */
    const val PERMISSION_MISSING = "permission_missing"

    const val NOT_PERMITTED =
        "The phone may not send text messages. Allow it in Settings, Apps, claude-voice, Permissions, SMS. " +
            "If the switch is greyed out, first choose Allow restricted settings on the app's info page."

    val TOOLS: List<ToolSpec> = listOf(
        ToolSpec(
            REQUEST,
            "Keep a draft text to one recipient and send nothing. to is a number in full international form, " +
                "a contact's name, or a pick. A name may answer choices instead of a draft: read them to the " +
                "owner, then call again with their chosen pick in to. Read a draft's say back to the owner word " +
                "for word, then confirm only after their spoken yes.",
            schema(
                """"to":{"type":"string","minLength":1},"text":{"type":"string","minLength":1}""",
                "to",
                "text",
            ),
        ),
        ToolSpec(
            CONFIRM,
            "Send the draft's stored text, after the owner's spoken yes to the read-back, once an undo delay " +
                "has passed. Only the latest version goes.",
            schema(
                """"token":{"type":"string","minLength":1},"version":{"type":"integer","minimum":1}""",
                "token",
                "version",
            ),
        ),
        ToolSpec(
            CANCEL,
            "Stop a draft or a text still waiting out its undo delay.",
            schema(""""token":{"type":"string","minLength":1}""", "token"),
        ),
    )

    /** The tool that needs the owner's spoken yes. */
    val CONFIRMS: List<String> = listOf(CONFIRM)

    /** One call, refused with [PERMISSION_MISSING] while the phone may not send ([permitted]). */
    fun call(
        outbox: Outbox,
        tool: String,
        arguments: JsonObject,
        permitted: Boolean,
        resolve: (String) -> Recipients.Resolved = Recipients::number,
    ): Reply {
        val spec = TOOLS.firstOrNull { it.name == tool }
        return when {
            spec == null -> Reply.Refused("sms_send has no tool $tool.")
            !permitted -> Reply.Refused(NOT_PERMITTED, PERMISSION_MISSING)
            arguments.keys != spec.keys -> Reply.Refused("$tool takes exactly ${spec.keys.joinToString()}.")
            else -> handed(outbox, tool, arguments, resolve)
                ?: Reply.Refused("$tool was given an argument of the wrong kind.")
        }
    }

    /** The call handed to the outbox, or null when an argument is of the wrong kind. */
    private fun handed(
        outbox: Outbox,
        tool: String,
        arguments: JsonObject,
        resolve: (String) -> Recipients.Resolved,
    ): Reply? {
        val token = text(arguments, "token")
        return when (tool) {
            REQUEST -> text(arguments, "to")?.let { to ->
                text(arguments, "text")?.let { outbox.request(to, it, resolve) }
            }
            CONFIRM -> token?.let { number(arguments)?.let { version -> outbox.confirm(token, version) } }
            else -> token?.let(outbox::cancel)
        }
    }

    private fun text(arguments: JsonObject, key: String): String? =
        (arguments[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun number(arguments: JsonObject): Int? =
        (arguments["version"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

    private fun schema(properties: String, vararg required: String): JsonObject = Json.parseToJsonElement(
        """{"type":"object","additionalProperties":false,"properties":{$properties},""" +
            """"required":[${required.joinToString(",") { "\"$it\"" }}]}""",
    ).jsonObject
}
