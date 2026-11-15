package org.claudevoice.mobile.core

import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The hostile set of ADR-CVM-005: each row of its table is a test here. */
class RequestReaderTest {
    private var now = 0L

    private fun read(text: String, perByte: Long = 0): ReadResult = read(text.toByteArray(Charsets.ISO_8859_1), perByte)

    private fun read(bytes: ByteArray, perByte: Long = 0): ReadResult {
        val ticking = object : InputStream() {
            val inner = ByteArrayInputStream(bytes)

            override fun read(): Int {
                now += perByte
                return inner.read()
            }
        }
        return RequestReader(clock = { now }).read(ticking)
    }

    private fun post(body: String = "{}", vararg extra: String, target: String = "/mcp") =
        "POST $target HTTP/1.1\r\nHost: 100.64.0.2:8812\r\n" + extra.joinToString("") { "$it\r\n" } +
            "Content-Length: ${body.length}\r\n\r\n$body"

    private fun status(result: ReadResult) = (result as ReadResult.Refused).status

    @Test
    fun `a plain POST is read whole`() {
        val r = read(post("""{"a":1}""", "Authorization: Bearer k")) as ReadResult.Request
        assertEquals("POST", r.method)
        assertEquals("/mcp", r.target)
        assertEquals("Bearer k", r.header("authorization"))
        assertEquals("100.64.0.2:8812", r.header("Host"))
        assertArrayEquals("""{"a":1}""".toByteArray(), r.body)
    }

    @Test
    fun `header values lose the spaces around them, and keep the ones inside`() {
        val r = read(post("{}", "X-A: \t one two \t")) as ReadResult.Request
        assertEquals("one two", r.header("x-a"))
    }

    @Test
    fun `the target is kept byte for byte`() {
        for (target in listOf("/%6Dcp", "/mcp/", "/mcp?x=1", "//mcp", "http://phone-a/mcp", "*")) {
            assertEquals(target, (read(post(target = target)) as ReadResult.Request).target, target)
        }
    }

    @Test
    fun `a GET needs no length`() {
        val r = read("GET /mcp HTTP/1.1\r\nHost: h\r\n\r\n") as ReadResult.Request
        assertEquals(0, r.body.size)
    }

    @Test
    fun `a version other than HTTP 1_1 is 505`() {
        for (version in listOf("HTTP/1.0", "HTTP/2", "http/1.1", "HTTP/1.10")) {
            assertEquals(505, status(read("POST /mcp $version\r\nHost: h\r\nContent-Length: 0\r\n\r\n")), version)
        }
    }

    @Test
    fun `a request line not of three parts is 400`() {
        for (line in listOf("POST /mcp", "POST  /mcp HTTP/1.1", "POST /mcp HTTP/1.1 x", "", " POST /mcp HTTP/1.1")) {
            assertEquals(400, status(read("$line\r\nHost: h\r\n\r\n")), line)
        }
    }

    @Test
    fun `a method that is not a token is 400`() {
        assertEquals(400, status(read("PO(ST /mcp HTTP/1.1\r\nHost: h\r\nContent-Length: 0\r\n\r\n")))
    }

    @Test
    fun `a POST with no length is 411`() {
        assertEquals(411, status(read("POST /mcp HTTP/1.1\r\nHost: h\r\n\r\n{}")))
    }

    @Test
    fun `two Content-Length headers are 400, even equal, in any case`() {
        assertEquals(400, status(read(post("{}", "Content-Length: 2"))))
        assertEquals(400, status(read(post("{}", "content-length: 2"))))
    }

    @Test
    fun `any Transfer-Encoding is 400, with or without a length`() {
        assertEquals(400, status(read(post("{}", "Transfer-Encoding: chunked"))))
        assertEquals(400, status(read("POST /mcp HTTP/1.1\r\nHost: h\r\nTransfer-Encoding: identity\r\n\r\n")))
    }

    @Test
    fun `no Host or two Hosts is 400`() {
        assertEquals(400, status(read("POST /mcp HTTP/1.1\r\nContent-Length: 0\r\n\r\n")))
        assertEquals(400, status(read(post("{}", "host: other"))))
    }

    @Test
    fun `a bare LF or a bare CR is 400`() {
        assertEquals(400, status(read("POST /mcp HTTP/1.1\nHost: h\r\nContent-Length: 0\r\n\r\n")))
        assertEquals(400, status(read("POST /mcp HTTP/1.1\r\nHost: h\nContent-Length: 0\r\n\r\n")))
        assertEquals(400, status(read("POST /mcp HTTP/1.1\r\nHost: h\rx\r\nContent-Length: 0\r\n\r\n")))
        assertEquals(400, status(read("POST /mcp HTTP/1.1\r\nHost: h\r\nContent-Length: 0\r\n\n")))
    }

    @Test
    fun `a folded header, or space before the colon, is 400`() {
        assertEquals(400, status(read(post("{}", "X-A: one", " two"))))
        assertEquals(400, status(read(post("{}", "X-A: one", "\ttwo"))))
        assertEquals(400, status(read(post("{}", "Host : h"))))
    }

    @Test
    fun `a header name that is not a token is 400`() {
        for (bad in listOf("X(A): 1", ": 1", "X A: 1", "Xé: 1", "No colon")) {
            assertEquals(400, status(read(post("{}", bad))), bad)
        }
    }

    @Test
    fun `a control byte in a value is 400, but a tab is not`() {
        assertEquals(400, status(read(post("{}", "X-A: a\u0001b"))))
        assertEquals(400, status(read(post("{}", "X-A: a\u007fb"))))
        assertTrue(read(post("{}", "X-A: a\tb")) is ReadResult.Request)
    }

    @Test
    fun `a byte outside printable ASCII in the target is 400`() {
        for (target in listOf("/mcé", "/m\u0001cp", "/m\u007fcp")) {
            assertEquals(400, status(read(post(target = target))), target)
        }
    }

    @Test
    fun `a length that is not plain digits, or overflows, is 400`() {
        for (length in listOf("+2", "-1", " ", "2 2", "0x2", "1e3", "99999999999999999999", "")) {
            val text = "POST /mcp HTTP/1.1\r\nHost: h\r\nContent-Length: $length\r\n\r\n{}"
            assertEquals(400, status(read(text)), length)
        }
    }

    @Test
    fun `an Expect header is 417`() {
        assertEquals(417, status(read(post("{}", "Expect: 100-continue"))))
    }

    @Test
    fun `a request line over 2 KiB is 414`() {
        assertTrue(read(post(target = "/" + "a".repeat(2048 - "POST / HTTP/1.1".length))) is ReadResult.Request)
        assertEquals(414, status(read(post(target = "/" + "a".repeat(2049 - "POST / HTTP/1.1".length)))))
    }

    @Test
    fun `headers over 8 KiB or 64 lines are 431`() {
        val big = "X-A: " + "a".repeat(8192)
        assertEquals(431, status(read(post("{}", big))))
        val lines = (1..62).map { "X-$it: 1" }.toTypedArray()
        assertTrue(read(post("{}", *lines)) is ReadResult.Request, "64 lines with Host and the length")
        assertEquals(431, status(read(post("{}", *lines, "X-63: 1"))))
    }

    @Test
    fun `a body over 256 KiB is 413, before any of it is read`() {
        val text = "POST /mcp HTTP/1.1\r\nHost: h\r\nContent-Length: 262145\r\n\r\n"
        assertEquals(413, status(read(text)))
        assertTrue(read(post("a".repeat(262144))) is ReadResult.Request)
    }

    @Test
    fun `a body shorter than its length is dropped, nothing handled`() {
        assertEquals(ReadResult.Dropped, read("POST /mcp HTTP/1.1\r\nHost: h\r\nContent-Length: 10\r\n\r\n{}"))
    }

    @Test
    fun `a head cut off is dropped`() {
        assertEquals(ReadResult.Dropped, read("POST /mcp HTTP/1.1\r\nHost: h\r\n"))
        assertEquals(ReadResult.Dropped, read(""))
    }

    @Test
    fun `a head sent slowly past five seconds is dropped`() {
        val text = post()
        val head = text.substringBefore("\r\n\r\n").length + 4
        assertTrue(read(text, perByte = 4_999_000_000L / head) is ReadResult.Request)
        assertEquals(ReadResult.Dropped, read(text, perByte = 5_001_000_000L / head + 1))
    }

    @Test
    fun `a body sent slowly past ten seconds is dropped`() {
        val body = "a".repeat(1000)
        val slow = object : InputStream() {
            val inner = ByteArrayInputStream(post(body).toByteArray())
            var head = true

            override fun read(): Int {
                val b = inner.read()
                if (!head) now += 10_001_000L
                if (b == '\n'.code && inner.available() == body.length) head = false
                return b
            }
        }
        assertEquals(ReadResult.Dropped, RequestReader(clock = { now }).read(slow))
    }

    @Test
    fun `every refusal is one of the fixed answers, with words for the hub`() {
        val r = read(post("{}", "Transfer-Encoding: chunked")) as ReadResult.Refused
        assertTrue(r.error.isNotBlank())
    }

    @Test
    fun `many short header lines over 8 KiB together are 431`() {
        val lines = (1..40).map { "X-$it: " + "a".repeat(200) }.toTypedArray()
        assertEquals(431, status(read(post("{}", *lines))))
        val fewer = (1..38).map { "X-$it: " + "a".repeat(200) }.toTypedArray()
        assertTrue(read(post("{}", *fewer)) is ReadResult.Request)
    }

    @Test
    fun `any header name sent twice, in any case, is 400`() {
        for (name in listOf("X-A", "Accept", "User-Agent")) {
            val r = read(post("{}", "$name: a", "${name.uppercase()}: b")) as ReadResult.Refused
            assertEquals(400, r.status, name)
            assertEquals("${name.lowercase()} is sent twice", r.error)
        }
    }

    @Test
    fun `the head limit runs from when the connection was accepted`() {
        now = 4_999_999_999L
        val r = RequestReader(clock = { now }).read(ByteArrayInputStream(post().toByteArray()), acceptedAt = 0L)
        assertTrue(r is ReadResult.Request, "$r")
        val late = RequestReader(clock = { now }).read(
            object : InputStream() {
                val inner = ByteArrayInputStream(post().toByteArray())

                override fun read(): Int {
                    now += 1
                    return inner.read()
                }
            },
            acceptedAt = 0L,
        )
        assertEquals(ReadResult.Dropped, late)
    }

    @Test
    fun `bytes after the body are left unread`() {
        val input = ByteArrayInputStream((post("{}") + "GET /mcp HTTP/1.1\r\n").toByteArray())
        assertTrue(RequestReader(clock = { 0L }).read(input) is ReadResult.Request)
        assertEquals("GET /mcp HTTP/1.1\r\n".length, input.available())
    }

    @Test
    fun `a length of 18 digits is read, and one that overflows a long is 400`() {
        assertTrue(read("POST /mcp HTTP/1.1\r\nHost: h\r\nContent-Length: 000000000000000002\r\n\r\n{}") is ReadResult.Request)
        assertEquals(400, status(read("POST /mcp HTTP/1.1\r\nHost: h\r\nContent-Length: 9223372036854775808\r\n\r\n")))
    }

    @Test
    fun `a NUL byte in the body is only data`() {
        val r = read(post("\u0000\u0000")) as ReadResult.Request
        assertArrayEquals(byteArrayOf(0, 0), r.body)
    }

    @Test
    fun `a byte read exactly at the deadline still counts`() {
        val text = post()
        val head = text.substringBefore("\r\n\r\n").length + 4
        // The clock moves per byte; the last byte of the head lands on five seconds exactly.
        assertTrue(read(text.substring(0, head) + "{}", perByte = 0) is ReadResult.Request)
        var t = 0L
        val exact = object : InputStream() {
            val inner = ByteArrayInputStream(text.toByteArray())
            var n = 0

            override fun read(): Int {
                n += 1
                t = if (n >= head) 5_000_000_000L else 0L
                return inner.read()
            }
        }
        assertTrue(RequestReader(clock = { t }).read(exact) !is ReadResult.Dropped)
    }

    @Test
    fun `the edges of the target and token ranges are accepted`() {
        assertEquals("/!~", (read(post(target = "/!~")) as ReadResult.Request).target)
        val r = read(post("{}", "azAZ09-!#\$%&'*+.^_`|~: 1")) as ReadResult.Request
        assertEquals("1", r.header("azaz09-!#\$%&'*+.^_`|~"))
        assertEquals("POST", (read(post()) as ReadResult.Request).method)
        assertTrue(read("aZ09 /mcp HTTP/1.1\r\nHost: h\r\n\r\n") is ReadResult.Request)
    }

    @Test
    fun `every header is handed on, by its lower-case name`() {
        val r = read(post("{}", "Authorization: Bearer k", "Origin: o")) as ReadResult.Request
        assertEquals(setOf("host", "authorization", "origin", "content-length"), r.headers().keys)
    }

    @Test
    fun `the characters just past the letter and digit ranges are not in a token`() {
        for (method in listOf("P{ST", "P[ST", "P:ST", "P@ST", "P`ST".replace("`", "/"))) {
            assertEquals(400, status(read("$method /mcp HTTP/1.1\r\nHost: h\r\nContent-Length: 0\r\n\r\n")), method)
        }
    }

    /** A stream that says "no data yet" [waits] times before each byte, as a socket with a short timeout does. */
    private fun waiting(text: String, waits: Int, tick: Long) = object : InputStream() {
        val inner = ByteArrayInputStream(text.toByteArray())
        var left = waits

        override fun read(): Int {
            if (left > 0) {
                left -= 1
                now += tick
                throw java.net.SocketTimeoutException("no data yet")
            }
            left = waits
            return inner.read()
        }
    }

    @Test
    fun `a read that times out with no data waits on, within the time limit`() {
        val r = RequestReader(clock = { now }).read(waiting(post(), waits = 2, tick = 1_000_000L))
        assertTrue(r is ReadResult.Request, "$r")
    }

    @Test
    fun `a client that sends nothing is dropped at the time limit`() {
        val r = RequestReader(clock = { now }).read(waiting(post(), waits = Int.MAX_VALUE, tick = 1_000_000_000L))
        assertEquals(ReadResult.Dropped, r)
    }

    @Test
    fun `a connection reset is a drop`() {
        val reset = object : InputStream() {
            override fun read(): Int = throw java.io.IOException("connection reset")
        }
        assertEquals(ReadResult.Dropped, RequestReader(clock = { now }).read(reset))
    }

    @Test
    fun `one header name repeated past 64 lines is 431`() {
        val lines = Array(65) { "X: 1" }
        assertEquals(431, status(read(post("{}", *lines))))
        assertEquals(431, status(read(post("{}", *Array(1000) { "X: 1" }))))
    }

    @Test
    fun `a doubled header the gate reads is 400`() {
        for (name in listOf("Authorization", "Origin", "Claude-Voice-Protocol", "Mcp-Session-Id")) {
            val r = read(post("{}", "$name: a", "${name.lowercase()}: b")) as ReadResult.Refused
            assertEquals(400, r.status, name)
            assertEquals("${name.lowercase()} is sent twice", r.error)
        }
    }
}
