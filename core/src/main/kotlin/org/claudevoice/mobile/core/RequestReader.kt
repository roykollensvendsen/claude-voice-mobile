package org.claudevoice.mobile.core

import java.io.InputStream

/** What reading one request gave: the request, a fixed refusal, or nothing to answer. */
sealed interface ReadResult {
    /** A request within the limits. [target] is the bytes as sent, never decoded or tidied. */
    class Request(
        val method: String,
        val target: String,
        private val headers: Map<String, String>,
        val body: ByteArray,
    ) : ReadResult {
        /** A header by name in any case. Each name appears once: a doubled one is refused earlier. */
        fun header(name: String): String? = headers[name.lowercase()]

        /** Every header, its name in lower case, for the gate. */
        fun headers(): Map<String, String> = headers
    }

    /** Refused with [status] and [error], the words of the `error.json` body; then the connection closes. */
    data class Refused(val status: Int, val error: String) : ReadResult

    /** Cut off or too slow: close the connection with no answer, and handle nothing. */
    data object Dropped : ReadResult
}

/**
 * Reads one HTTP/1.1 request, as ADR-CVM-005 sets: the framing rules, the limits and the fixed
 * refusals. It reads byte by byte from [InputStream] (give it a buffered one) and checks [clock],
 * a monotonic clock in nanoseconds, after every byte, so a slow sender is dropped on time.
 */
class RequestReader(private val clock: () -> Long) {
    fun read(input: InputStream): ReadResult = try {
        Reading(input, clock).request()
    } catch (refusal: Refusal) {
        refusal.result
    }

    /** One refusal or drop, thrown out of the reading at once. */
    private class Refusal(val result: ReadResult) : RuntimeException(null, null, false, false)

    private class Reading(private val input: InputStream, private val clock: () -> Long) {
        private val started = clock()
        private var deadline = started + HEAD_NANOS

        fun request(): ReadResult.Request {
            val (method, target) = requestLine(line(LINE_LIMIT, URI_TOO_LONG))
            val headers = headers()
            val body = body(method, headers)
            return ReadResult.Request(method, target, headers, body)
        }

        private fun requestLine(line: String): Pair<String, String> {
            val parts = line.split(' ')
            if (parts.size != REQUEST_LINE_PARTS) {
                refuse(BAD_REQUEST, "a request line is a method, a target and a version")
            }
            val (method, target, version) = parts
            if (method.isEmpty() || !method.all(::isTokenChar)) {
                refuse(BAD_REQUEST, "the method is not a token")
            }
            if (target.isEmpty() || !target.all { it in '!'..'~' }) {
                refuse(BAD_REQUEST, "the target has a byte outside printable ASCII")
            }
            if (version != VERSION) refuse(VERSION_NOT_SUPPORTED, "only HTTP/1.1 is spoken")
            return method to target
        }

        private fun headers(): Map<String, String> {
            val seen = mutableMapOf<String, String>()
            val doubled = mutableSetOf<String>()
            var used = 0
            while (true) {
                val line = line(HEADERS_LIMIT - used, HEADERS_TOO_LARGE)
                if (line.isEmpty()) break
                used += line.length + 2
                if (seen.size + doubled.size >= HEADER_LINES) {
                    refuse(HEADERS_TOO_LARGE, "more than $HEADER_LINES header lines")
                }
                val (name, value) = header(line)
                if (seen.put(name, value) != null) doubled += name
            }
            return checked(seen, doubled)
        }

        private fun header(line: String): Pair<String, String> {
            val colon = line.indexOf(':')
            val name = if (colon > 0) {
                line.substring(0, colon)
            } else {
                refuse(BAD_REQUEST, "a header line is a name, a colon and a value")
            }
            if (!name.all(::isTokenChar)) {
                refuse(BAD_REQUEST, "a header name is not a token, or is folded or spaced")
            }
            val value = line.substring(colon + 1).trim(' ', '\t')
            if (value.any { (it < ' ' && it != '\t') || it == DEL }) {
                refuse(BAD_REQUEST, "a header value has a control byte")
            }
            return name.lowercase() to value
        }

        private fun checked(headers: Map<String, String>, doubled: Set<String>): Map<String, String> {
            when {
                TRANSFER_ENCODING in headers -> refuse(BAD_REQUEST, "send Content-Length, not Transfer-Encoding")
                CONTENT_LENGTH in doubled -> refuse(BAD_REQUEST, "Content-Length is sent twice")
                HOST !in headers || HOST in doubled -> refuse(BAD_REQUEST, "send one Host header")
                EXPECT in headers -> refuse(EXPECTATION_FAILED, "Expect is not accepted")
            }
            return headers
        }

        private fun body(method: String, headers: Map<String, String>): ByteArray {
            val declared = headers[CONTENT_LENGTH]
            if (declared == null && method == POST) refuse(LENGTH_REQUIRED, "a POST needs a Content-Length")
            val length = declared?.let(::length) ?: 0
            deadline = clock() + BODY_NANOS
            return ByteArray(length) { next().toByte() }
        }

        private fun length(text: String): Int {
            val plain = text.isNotEmpty() && text.length <= MAX_DIGITS && text.all { it in '0'..'9' }
            val value = if (plain) {
                text.toLong()
            } else {
                refuse(BAD_REQUEST, "Content-Length is not plain digits")
            }
            if (value > BODY_LIMIT) refuse(CONTENT_TOO_LARGE, "the body is larger than $BODY_LIMIT bytes")
            return value.toInt()
        }

        /** A line ending in CRLF, without it, of at most [limit] bytes; [overStatus] beyond. */
        private fun line(limit: Int, overStatus: Int): String {
            val text = StringBuilder()
            while (true) {
                val byte = next()
                if (byte == CR) {
                    if (next() != LF) refuse(BAD_REQUEST, "a bare CR")
                    return text.toString()
                }
                if (byte == LF) refuse(BAD_REQUEST, "a line ends in a bare LF; end it in CRLF")
                if (text.length >= limit) refuse(overStatus, "a line is too long")
                text.append(byte.toChar())
            }
        }

        /** The next byte; dropped at the end of the stream or past the deadline. */
        private fun next(): Int {
            val byte = input.read()
            if (byte < 0 || clock() - deadline > 0) throw Refusal(ReadResult.Dropped)
            return byte
        }

        private fun refuse(status: Int, error: String): Nothing = throw Refusal(ReadResult.Refused(status, error))
    }

    private companion object {
        const val VERSION = "HTTP/1.1"
        const val POST = "POST"
        const val HOST = "host"
        const val CONTENT_LENGTH = "content-length"
        const val TRANSFER_ENCODING = "transfer-encoding"
        const val EXPECT = "expect"
        const val REQUEST_LINE_PARTS = 3
        const val LINE_LIMIT = 2048
        const val HEADERS_LIMIT = 8192
        const val HEADER_LINES = 64
        const val BODY_LIMIT = 262_144L
        const val MAX_DIGITS = 18
        const val HEAD_NANOS = 5_000_000_000L
        const val BODY_NANOS = 10_000_000_000L
        const val CR = '\r'.code
        const val LF = '\n'.code
        const val DEL = '\u007f'
        const val BAD_REQUEST = 400
        const val LENGTH_REQUIRED = 411
        const val CONTENT_TOO_LARGE = 413
        const val URI_TOO_LONG = 414
        const val EXPECTATION_FAILED = 417
        const val HEADERS_TOO_LARGE = 431
        const val VERSION_NOT_SUPPORTED = 505
        const val TOKEN_MARKS = "!#$%&'*+-.^_`|~"

        fun isTokenChar(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in TOKEN_MARKS
    }
}
