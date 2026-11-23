package org.claudevoice.mobile.plugins.sms_send.rules

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import java.io.IOException

/** A tool as the plugin offers it; the plugin turns it into the interface's own type. */
data class ToolSpec(val name: String, val description: String, val input: JsonObject) {
    /** The arguments the tool takes; a sending tool requires every one of them. */
    val keys: Set<String> get() = input.getValue("properties").jsonObject.keys
}

/**
 * The three sending tools (messages-v1 §8, choice B) and the poll of how each text ended
 * (ADR-CVM-010): their names, inputs and how a call is read. The names are fixed with the hub,
 * whose deny rule matches `sms_send_confirm`: none of them is marked read-only, and only confirm
 * needs the owner's spoken yes.
 */
object SmsSend {
    const val REQUEST = "sms_send_request"
    const val CONFIRM = "sms_send_confirm"
    const val CANCEL = "sms_send_cancel"
    const val OUTCOMES = "sms_send_outcomes"

    /** What the hub hears when an answer could not be saved: nothing was handed out, so asking again is safe. */
    const val NOT_SAVED = "The phone could not save its outcomes, so nothing was handed out. Ask again."

    /** The record's sentence the hub hands the voice with the tool (ADR-CVM-010); a test keeps them alike. */
    const val TOOL_WORDS =
        "When dropped is above 0, say that some outcomes were lost and that a text may have been " +
            "sent; check the text app."

    private const val CURSOR = "cursor"
    private const val NO_OUTCOMES = "The phone keeps no outcomes of its texts yet."
    private const val WRONG_CURSOR =
        "sms_send_outcomes takes only a cursor: the string from the last answer, or none at all."

    /** Protocol version 1's code for a refusal the owner can lift by granting a permission. */
    const val PERMISSION_MISSING = "permission_missing"

    /** The most recipients one request may name. */
    const val MOST_RECIPIENTS = 5

    const val NOT_PERMITTED =
        "The phone may not send text messages. Allow it in Settings, Apps, claude-voice, Permissions, SMS. " +
            "If the switch is greyed out, first choose Allow restricted settings on the app's info page."

    val TOOLS: List<ToolSpec> = listOf(
        ToolSpec(
            REQUEST,
            "Keep a draft text and send nothing. to is a number in full international form, a contact's name, " +
                "or a pick, or a list of up to five of these for one text to each. A name may answer choices " +
                "instead of a draft: read them to the owner, then call again with the same to and their chosen " +
                "pick in place of that name. Read a draft's say back to the owner word for word, then confirm " +
                "only after their spoken yes; one yes, one undo delay and one cancel cover everyone in it. A number " +
                "may be marked not in the contacts or never texted before. The never-texted mark needs the app to " +
                "read texts; without that it is left out.",
            schema(
                """"to":{"oneOf":[{"type":"string","minLength":1},""" +
                    """{"type":"array","items":{"type":"string","minLength":1},"minItems":1,""" +
                    """"maxItems":$MOST_RECIPIENTS}]},"text":{"type":"string","minLength":1}""",
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
        ToolSpec(
            OUTCOMES,
            "How each text the phone sent ended, oldest first, since the last call. Pass back the cursor of " +
                "the last answer: that acknowledges it. $TOOL_WORDS",
            Json.parseToJsonElement(
                """{"type":"object","additionalProperties":false,""" +
                    """"properties":{"$CURSOR":{"type":["string","null"],"minLength":1}}}""",
            ).jsonObject,
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
        recipients: Recipients? = null,
    ): Reply {
        val spec = TOOLS.firstOrNull { it.name == tool }
        return when {
            spec == null -> Reply.Refused("sms_send has no tool $tool.")
            tool == OUTCOMES -> Reply.Refused(NO_OUTCOMES)
            !permitted -> Reply.Refused(NOT_PERMITTED, PERMISSION_MISSING)
            arguments.keys != spec.keys -> Reply.Refused("$tool takes exactly ${spec.keys.joinToString()}.")
            else -> handed(outbox, tool, arguments, recipients)
                ?: Reply.Refused("$tool was given an argument of the wrong kind.")
        }
    }

    /**
     * The poll's answer from [journal], or a refusal when the arguments do not fit its input. It
     * answers even when sending was taken back: an outcome holds no number, name or text.
     */
    fun outcomes(journal: Journal?, arguments: JsonObject): Reply {
        val cursor = arguments[CURSOR]
        val text = text(arguments, CURSOR)
        val given = cursor == null || cursor is JsonNull || !text.isNullOrEmpty()
        val fits = (arguments.keys - CURSOR).isEmpty() && given
        return when {
            journal == null -> Reply.Refused(NO_OUTCOMES)
            !fits -> Reply.Refused(WRONG_CURSOR)
            else -> try {
                Reply.Answer(journal.since(text))
            } catch (_: IOException) {
                Reply.Refused(NOT_SAVED)
            }
        }
    }

    /** The call handed to the outbox, or null when an argument is of the wrong kind. */
    private fun handed(
        outbox: Outbox,
        tool: String,
        arguments: JsonObject,
        recipients: Recipients?,
    ): Reply? {
        val token = text(arguments, "token")
        return when (tool) {
            REQUEST -> addressed(arguments)?.let { to ->
                text(arguments, "text")?.let { outbox.request(to, it, recipients) }
            }
            CONFIRM -> token?.let { number(arguments)?.let { version -> outbox.confirm(token, version) } }
            else -> token?.let(outbox::cancel)
        }
    }

    /** `to` as a list: one string, or a list of one to [MOST_RECIPIENTS] strings; null for anything else. */
    private fun addressed(arguments: JsonObject): List<String>? = when (val to = arguments["to"]) {
        is JsonArray -> to.takeIf { it.size in 1..MOST_RECIPIENTS }
            ?.map { (it as? JsonPrimitive)?.takeIf { one -> one.isString }?.content }
            ?.takeIf { null !in it }
            ?.filterNotNull()
        else -> text(arguments, "to")?.let(::listOf)
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
