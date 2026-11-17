package org.claudevoice.mobile.core

import java.net.InetAddress
import java.time.Duration
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.claudevoice.mobile.pluginapi.Plugin
import org.claudevoice.mobile.core.McpJson.answered
import org.claudevoice.mobile.core.McpJson.obj
import org.claudevoice.mobile.core.McpJson.refusal
import org.claudevoice.mobile.core.McpJson.refused
import org.claudevoice.mobile.core.McpJson.replied
import org.claudevoice.mobile.pluginapi.ToolResult

/**
 * The phone as an MCP server, behind the gate at `POST /mcp`: agent-protocol version 1's MCP
 * section over ADR-CVM-005's server. One JSON-RPC message per request, answered in plain JSON,
 * with no session. The hub uses only `initialize`, `tools/list` and `tools/call`; `ping` is
 * answered too, and a notification is accepted with `202` and an empty body.
 *
 * A body that is not one JSON-RPC message, such as bad JSON or a batch, is refused with `400`
 * and a JSON-RPC error with no id, as MCP's HTTP transport says. A method or its parameters that
 * are wrong get `200` with a JSON-RPC error.
 *
 * A call runs at most once per `claude-voice/call_id` (see [CallIds]). The id is claimed only when
 * the call is about to run, after its tool is found and its arguments read, so a send refused
 * before that leaves the id free for its retry. A call gets [callLimit]; past it the answer says
 * it may still finish, and its id stays kept, so a retry cannot run it twice. [failed] hears only
 * the class of an exception a plugin threw, never its message.
 */
class McpHandler(
    private val plugins: Plugins,
    private val softwareVersion: String,
    clock: (() -> Long)? = null,
    callLimit: Duration = CALL_LIMIT,
    keptIds: Int = CallIds.CAPACITY,
    private val failed: ((String) -> Unit)? = null,
) : Handler {
    private val ids = CallIds(clock, capacity = keptIds)
    private val calls = PluginCall(callLimit)
    private val limitSeconds = callLimit.seconds

    /** The methods the phone answers, by name; a map rather than a `when`, so each is one lookup. */
    private val methods: Map<String, (JsonElement?) -> McpReply> = mapOf(
        "initialize" to { params -> McpReply.Result(initialized(params as? JsonObject)) },
        "ping" to { _ -> McpReply.Result(obj()) },
        "tools/list" to { _ -> McpReply.Result(listed()) },
        "tools/call" to { params -> called(params as? JsonObject) },
    )

    override fun handle(request: ReadResult.Request, peer: InetAddress): Answer {
        val message = parsed(request.body) ?: return refused(PARSE_ERROR, "the body is not JSON")
        val request = (message as? JsonObject)?.takeIf { McpJson.isJsonRpc(it) }
        val method = (request?.get("method") as? JsonPrimitive)?.takeIf { it.isString }?.content
        val id = request?.get("id")
        return when {
            request == null || method == null || !validId(id) ->
                refused(INVALID_REQUEST, "the body is not one JSON-RPC 2.0 request")
            id == null -> Answer(ACCEPTED, ByteArray(0))
            else -> replied(id, answer(method, request["params"]))
        }
    }

    private fun answer(method: String, params: JsonElement?): McpReply =
        methods[method]?.invoke(params) ?: McpReply.Error(METHOD_NOT_FOUND, "the phone does not answer $method")

    private fun initialized(params: JsonObject?): JsonObject {
        val asked = (params?.get("protocolVersion") as? JsonPrimitive)?.content
        return obj(
            "protocolVersion" to JsonPrimitive(asked?.takeIf { it in KNOWN } ?: LATEST),
            "capabilities" to obj("tools" to obj()),
            "serverInfo" to obj("name" to JsonPrimitive(SERVER_NAME), "version" to JsonPrimitive(softwareVersion)),
        )
    }

    private fun listed(): JsonObject = obj(
        "tools" to JsonArray(
            plugins.tools().map {
                obj(
                    "name" to JsonPrimitive(it.name),
                    "description" to JsonPrimitive(it.description),
                    "inputSchema" to it.input,
                )
            },
        ),
    )

    private fun called(params: JsonObject?): McpReply {
        val name = (params?.get("name") as? JsonPrimitive)?.takeIf { it.isString }?.content
        val arguments = params?.get("arguments") ?: obj()
        val plugin = name?.let { plugins.owner(it) }
        return when {
            name == null || arguments !is JsonObject -> McpReply.Error(INVALID_PARAMS, NEEDS_NAME)
            plugin == null -> McpReply.Result(refusal("The phone offers no tool $name."))
            else -> McpReply.Result(ran(plugin, name, arguments, callIdIn(params)))
        }
    }

    private fun ran(plugin: Plugin, tool: String, arguments: JsonObject, callId: String?): JsonObject =
        when (callId?.let { ids.claim(it) } ?: CallIds.Claim.RUN) {
            CallIds.Claim.DUPLICATE -> refusal(
                obj(
                    "error" to JsonPrimitive("The phone has already run this call of $tool, so not again."),
                    "code" to JsonPrimitive(DUPLICATE_CALL),
                ),
            )
            CallIds.Claim.BUSY -> refusal(BUSY)
            CallIds.Claim.RUN -> outcome(tool, calls.run(plugin, tool, arguments), callId)
        }

    private fun outcome(tool: String, outcome: CallOutcome, callId: String?): JsonObject = when (outcome) {
        is CallOutcome.Done -> when (val result = outcome.result) {
            is ToolResult.Answer -> answered(result.content)
            is ToolResult.Refused -> refusal(result.say)
        }
        CallOutcome.Late -> refusal("$tool took longer than $limitSeconds seconds. It may still finish on the phone.")
        CallOutcome.NotRun -> {
            ids.release(callId)
            refusal("The phone was too busy to start $tool in $limitSeconds seconds, so it did not run.")
        }
        is CallOutcome.Broke -> {
            failed?.invoke(outcome.kind)
            refusal("The phone could not run $tool.")
        }
    }

    companion object {
        const val LATEST = "2025-06-18"
        private val KNOWN = setOf("2025-03-26", LATEST)
        const val SERVER_NAME = "claude-voice-mobile"
        const val DUPLICATE_CALL = "duplicate_call"
        const val CALL_ID = "claude-voice/call_id"
        val CALL_LIMIT: Duration = Duration.ofSeconds(20)

        const val PARSE_ERROR = -32700
        const val INVALID_REQUEST = -32600
        const val METHOD_NOT_FOUND = -32601
        const val INVALID_PARAMS = -32602

        private const val ACCEPTED = 202
        private const val NEEDS_NAME = "a call needs a name and object arguments"
        private const val BUSY =
            "The phone is keeping too many recent calls to take another. Try again in a few minutes."

        /** The body as JSON, or null when it is not JSON at all. */
        private fun parsed(body: ByteArray): JsonElement? = try {
            Json.parseToJsonElement(String(body, Charsets.UTF_8))
        } catch (_: SerializationException) {
            null
        }

        /** No id, or one that is a string or a number, as JSON-RPC allows; never null, true or false. */
        private fun validId(id: JsonElement?) =
            id == null || (id is JsonPrimitive && (id.isString || id.content.toDoubleOrNull() != null))

        private fun callIdIn(params: JsonObject): String? =
            ((params["_meta"] as? JsonObject)?.get(CALL_ID) as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}
