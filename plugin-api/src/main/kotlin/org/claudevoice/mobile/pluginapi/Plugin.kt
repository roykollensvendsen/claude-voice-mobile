package org.claudevoice.mobile.pluginapi

import kotlinx.serialization.json.JsonObject

/**
 * One function of the phone, offered to the hub: its name, its tools and what each takes.
 *
 * A plugin sees nothing of the core or of another plugin. Everything it needs comes through
 * this module, which is what lets one be added or left out without touching the rest.
 */
interface Plugin {
    /** Lowercase letters, digits and underscores, starting with a letter. */
    val name: String

    val tools: List<Tool>

    /** The answer to one call of one of [tools], with arguments that fit its [Tool.input]. */
    suspend fun call(tool: String, arguments: JsonObject): ToolResult
}

/** A tool as the hub sees it: [input] is a JSON schema of its arguments. */
data class Tool(val name: String, val description: String, val input: JsonObject)

/** What a call answers: a structured answer, or a refusal said in words a person can act on. */
sealed interface ToolResult {
    data class Answer(val content: JsonObject) : ToolResult

    data class Refused(val say: String) : ToolResult
}
