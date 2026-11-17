package org.claudevoice.mobile.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** What one JSON-RPC method answers: a result, or an error with its code. */
internal sealed interface McpReply {
    data class Result(val result: JsonObject) : McpReply

    data class Error(val code: Int, val message: String) : McpReply
}

/**
 * The shapes of the phone's MCP answers. Built as plain objects, not with the JSON builders,
 * whose small lambdas would leave Pitest mutants no test can see.
 */
internal object McpJson {
    private const val OK = 200
    private const val BAD_REQUEST = 400
    private val JSONRPC = JsonPrimitive("2.0")

    fun obj(vararg fields: Pair<String, JsonElement>) = JsonObject(mapOf(*fields))

    fun text(words: String) = JsonArray(listOf(obj("type" to JsonPrimitive("text"), "text" to JsonPrimitive(words))))

    /** A tool's answer, structured and as the same JSON in text, for a hub that reads only text. */
    fun answered(content: JsonObject) = obj(
        "content" to text(content.toString()),
        "structuredContent" to content,
        "isError" to JsonPrimitive(false),
    )

    /** A tool's refusal, in words a person can act on. */
    fun refusal(say: String) = obj("content" to text(say), "isError" to JsonPrimitive(true))

    /** A refusal that also carries `error.json`, structured, such as `duplicate_call`. */
    fun refusal(error: JsonObject) =
        obj("content" to text(error.toString()), "structuredContent" to error, "isError" to JsonPrimitive(true))

    /** A JSON-RPC reply to the request [id], with `200`. */
    fun replied(id: JsonElement, reply: McpReply): Answer {
        val outcome = when (reply) {
            is McpReply.Result -> "result" to reply.result
            is McpReply.Error -> "error" to error(reply.code, reply.message)
        }
        return json(OK, obj("jsonrpc" to JSONRPC, "id" to id, outcome))
    }

    /** A body that is not one JSON-RPC request: `400`, and an error with no id. */
    fun refused(code: Int, message: String): Answer =
        json(BAD_REQUEST, obj("jsonrpc" to JSONRPC, "id" to JsonNull, "error" to error(code, message)))

    fun isJsonRpc(message: JsonObject) = message["jsonrpc"] == JSONRPC

    private fun error(code: Int, message: String) =
        obj("code" to JsonPrimitive(code), "message" to JsonPrimitive(message))

    private fun json(status: Int, body: JsonObject) = Answer(status, body.toString().toByteArray())
}
