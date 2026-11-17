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
import org.claudevoice.mobile.pluginapi.Tool
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
 * it may still finish, and its id stays kept, so a retry cannot run it twice.
 *
 * Every call is written to [log] with what came of it (ADR-CVM-003): the tool, the hub, the
 * caller from `_meta`, and for a plugin that threw, only the exception's class. A request that
 * is no call, such as `tools/list` or a malformed call, is not logged.
 */
class McpHandler(
    private val info: AgentInfo,
    clock: (() -> Long)? = null,
    callLimit: Duration = CALL_LIMIT,
    keptIds: Int = CallIds.CAPACITY,
    private val log: CallLog,
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
            "serverInfo" to obj("name" to JsonPrimitive(SERVER_NAME), "version" to JsonPrimitive(info.version)),
        )
    }

    private fun listed(): JsonObject = obj(
        "tools" to JsonArray(
            (listOf(ABOUT) + info.plugins.tools()).map {
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
        return if (name == null || arguments !is JsonObject) {
            McpReply.Error(INVALID_PARAMS, NEEDS_NAME)
        } else {
            McpReply.Result(chosen(name, arguments, params, Asked(name, callerIn(params))))
        }
    }

    /** The tool asked for: `agent_info`, a plugin's, or none. */
    private fun chosen(name: String, arguments: JsonObject, params: JsonObject, asked: Asked): JsonObject {
        val plugin = info.plugins.owner(name)
        return when {
            // Reads only, so it is answered at once, with no call id kept.
            name == AgentInfo.TOOL -> about(asked)
            plugin == null -> asked.came(CallLog.Outcome.REFUSED, refusal("The phone offers no tool $name."))
            else -> ran(plugin, name, arguments, callIdIn(params), asked)
        }
    }

    /**
     * `agent_info`, unless a plugin now says it uses a name the protocol does not know: then a
     * refusal that names the plugin, and is logged, rather than a report the hub would refuse.
     */
    private fun about(asked: Asked): JsonObject {
        val unknown = info.plugins.unknownUse()
        return if (unknown == null) {
            asked.came(CallLog.Outcome.ANSWERED, answered(info.report()))
        } else {
            asked.came(
                CallLog.Outcome.REFUSED,
                refusal(
                    "The phone's plugin $unknown reports a use the protocol does not know, " +
                        "so the phone cannot say what it offers.",
                ),
            )
        }
    }

    private fun ran(plugin: Plugin, tool: String, arguments: JsonObject, callId: String?, asked: Asked): JsonObject =
        when (callId?.let { ids.claim(it) } ?: CallIds.Claim.RUN) {
            CallIds.Claim.DUPLICATE -> asked.came(
                CallLog.Outcome.DUPLICATE,
                refusal(
                    obj(
                        "error" to JsonPrimitive("The phone has already run this call of $tool, so not again."),
                        "code" to JsonPrimitive(DUPLICATE_CALL),
                    ),
                ),
            )
            CallIds.Claim.BUSY -> asked.came(CallLog.Outcome.BUSY, refusal(BUSY))
            CallIds.Claim.RUN -> outcome(tool, calls.run(plugin, tool, arguments), callId, asked)
        }

    private fun outcome(tool: String, outcome: CallOutcome, callId: String?, asked: Asked): JsonObject =
        when (outcome) {
            is CallOutcome.Done -> when (val result = outcome.result) {
                is ToolResult.Answer -> asked.came(CallLog.Outcome.ANSWERED, answered(result.content))
                is ToolResult.Refused -> asked.came(CallLog.Outcome.REFUSED, refusal(result.say))
            }
            CallOutcome.Late -> asked.came(
                CallLog.Outcome.LATE,
                refusal("$tool took longer than $limitSeconds seconds. It may still finish on the phone."),
            )
            CallOutcome.NotRun -> {
                ids.release(callId)
                asked.came(
                    CallLog.Outcome.NOT_RUN,
                    refusal("The phone was too busy to start $tool in $limitSeconds seconds, so it did not run."),
                )
            }
            is CallOutcome.Broke -> asked.broke(outcome.kind, refusal("The phone could not run $tool."))
        }

    /** One call as the log knows it: its tool and who asked. Its fields are its own, so it has no getters. */
    private inner class Asked(private val tool: String, private val caller: String?) {
        /** Logs what came of the call, and gives back its [answer]. */
        fun came(outcome: CallLog.Outcome, answer: JsonObject): JsonObject {
            log.record(tool, info.hub(), caller, outcome, null)
            return answer
        }

        /** Logs a plugin that threw, with only the class of what it threw. */
        fun broke(kind: String, answer: JsonObject): JsonObject {
            log.record(tool, info.hub(), caller, CallLog.Outcome.BROKE, kind)
            return answer
        }
    }

    companion object {
        const val LATEST = "2025-06-18"
        private val KNOWN = setOf("2025-03-26", LATEST)
        const val SERVER_NAME = "claude-voice-mobile"
        private val ABOUT = Tool(
            AgentInfo.TOOL,
            "What this phone is: its name, address and hub, the tools and plugins it offers, what each uses, " +
                "and its privacy settings.",
            JsonObject(mapOf("type" to JsonPrimitive("object"))),
        )
        const val DUPLICATE_CALL = "duplicate_call"
        const val CALL_ID = "claude-voice/call_id"
        const val CALLER = "claude-voice/caller"
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

        private fun callerIn(params: JsonObject): String? =
            ((params["_meta"] as? JsonObject)?.get(CALLER) as? JsonPrimitive)?.takeIf { it.isString }?.content

        private fun callIdIn(params: JsonObject): String? =
            ((params["_meta"] as? JsonObject)?.get(CALL_ID) as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}
