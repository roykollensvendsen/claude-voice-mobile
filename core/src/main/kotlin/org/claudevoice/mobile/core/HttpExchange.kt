package org.claudevoice.mobile.core

import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** An answer to send: its status, extra headers, and a JSON body. */
class Answer(val status: Int, val body: ByteArray, val headers: Map<String, String> = emptyMap()) {
    companion object {
        /** A refusal with protocol version 1's `error.json` body. */
        fun refusal(status: Int, error: String, headers: Map<String, String> = emptyMap()) = Answer(
            status,
            buildJsonObject { put("error", JsonPrimitive(error)) }.toString().toByteArray(),
            headers,
        )
    }
}

/** What answers a request that reached `/pair` or `/mcp` by `POST`, from [InetAddress]. */
fun interface Handler {
    fun handle(request: ReadResult.Request, peer: InetAddress): Answer
}

/**
 * One request in, one answer out, as ADR-CVM-005 sets. A request the reader refuses is answered
 * with its refusal; one it drops gets no answer. Only a `POST` to `/pair` or `/mcp`, exactly as
 * written, reaches [handler]: another target is `404`, and another method `405`. Every answer
 * carries `Content-Length`, `Content-Type: application/json` and `Connection: close`.
 */
class HttpExchange(private val reader: RequestReader, private val handler: Handler) {
    fun run(input: InputStream, output: OutputStream, peer: InetAddress) {
        val answer = when (val read = reader.read(input)) {
            ReadResult.Dropped -> return
            is ReadResult.Refused -> Answer.refusal(read.status, read.error)
            is ReadResult.Request -> route(read, peer)
        }
        write(answer, output)
    }

    private fun route(request: ReadResult.Request, peer: InetAddress): Answer = when {
        request.target != PAIR && request.target != MCP -> Answer.refusal(NOT_FOUND, "only /pair and /mcp are served")
        request.method != POST -> Answer.refusal(METHOD_NOT_ALLOWED, "only POST is served", mapOf("Allow" to POST))
        else -> handler.handle(request, peer)
    }

    private fun write(answer: Answer, output: OutputStream) {
        val head = StringBuilder("HTTP/1.1 ${answer.status} ${REASONS[answer.status] ?: ""}".trimEnd()).append(CRLF)
        val headers = answer.headers + mapOf(
            "Content-Type" to "application/json",
            "Content-Length" to "${answer.body.size}",
            "Connection" to "close",
        )
        headers.forEach { (name, value) -> head.append(name).append(": ").append(value).append(CRLF) }
        output.write(head.append(CRLF).toString().toByteArray(Charsets.ISO_8859_1))
        output.write(answer.body)
        output.flush()
    }

    private companion object {
        const val PAIR = "/pair"
        const val MCP = "/mcp"
        const val POST = "POST"
        const val CRLF = "\r\n"
        const val NOT_FOUND = 404
        const val METHOD_NOT_ALLOWED = 405

        @Suppress("MagicNumber")
        val REASONS = mapOf(
            200 to "OK",
            400 to "Bad Request",
            401 to "Unauthorized",
            403 to "Forbidden",
            404 to "Not Found",
            405 to "Method Not Allowed",
            411 to "Length Required",
            413 to "Content Too Large",
            414 to "URI Too Long",
            417 to "Expectation Failed",
            431 to "Request Header Fields Too Large",
            500 to "Internal Server Error",
            505 to "HTTP Version Not Supported",
        )
    }
}
