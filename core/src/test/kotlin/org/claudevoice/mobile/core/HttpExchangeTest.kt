package org.claudevoice.mobile.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** One request in, one answer out, as ADR-CVM-005 sets; no socket. */
class HttpExchangeTest {
    private val peer = InetAddress.getByName("100.64.0.1")
    private val handled = mutableListOf<ReadResult.Request>()
    private val exchange = HttpExchange(RequestReader(clock = { 0L })) { request, from ->
        handled += request
        assertEquals(peer, from)
        Answer(200, """{"ok":true}""".toByteArray())
    }

    private fun answer(text: String): String {
        val out = ByteArrayOutputStream()
        exchange.run(ByteArrayInputStream(text.toByteArray(Charsets.ISO_8859_1)), out, peer)
        return out.toString(Charsets.ISO_8859_1)
    }

    private fun request(method: String = "POST", target: String = "/mcp") =
        "$method $target HTTP/1.1\r\nHost: h\r\nContent-Length: 2\r\n\r\n{}"

    private fun status(answer: String) = answer.substringBefore("\r\n")

    @Test
    fun `a POST to mcp or pair reaches the handler, and the answer is framed`() {
        val text = answer(request())
        assertEquals("HTTP/1.1 200 OK", status(text))
        assertTrue(text.contains("\r\nContent-Type: application/json\r\n"), text)
        assertTrue(text.contains("\r\nContent-Length: 11\r\n"), text)
        assertTrue(text.contains("\r\nConnection: close\r\n"), text)
        assertTrue(text.endsWith("\r\n\r\n{\"ok\":true}"), text)
        answer(request(target = "/pair"))
        assertEquals(listOf("/mcp", "/pair"), handled.map { it.target })
    }

    @Test
    fun `any other target is 404, and never reaches the handler`() {
        for (target in listOf("/", "/mcp/", "/%6Dcp", "/mcp?x=1", "//mcp", "/PAIR", "http://phone-a/mcp", "*")) {
            assertEquals("HTTP/1.1 404 Not Found", status(answer(request(target = target))), target)
        }
        assertTrue(handled.isEmpty())
    }

    @Test
    fun `the method is matched in exact case, and GET or DELETE on mcp is 405`() {
        for (method in listOf("post", "Post", "GET", "DELETE", "PUT")) {
            assertEquals("HTTP/1.1 405 Method Not Allowed", status(answer(request(method = method))), method)
        }
        assertEquals("HTTP/1.1 405 Method Not Allowed", status(answer(request(method = "GET", target = "/pair"))))
        assertTrue(handled.isEmpty())
    }

    @Test
    fun `a 405 names the method allowed`() {
        assertTrue(answer(request(method = "GET")).contains("\r\nAllow: POST\r\n"))
    }

    @Test
    fun `a refused request is answered with error_json and never handled`() {
        val text = answer("POST /mcp HTTP/1.1\r\nHost: h\r\nTransfer-Encoding: chunked\r\n\r\n")
        assertEquals("HTTP/1.1 400 Bad Request", status(text))
        assertTrue(text.substringAfter("\r\n\r\n").startsWith("{\"error\":"), text)
        assertTrue(handled.isEmpty())
    }

    @Test
    fun `each refusal status has its reason phrase`() {
        val cases = mapOf(
            "POST /mcp HTTP/1.0\r\nHost: h\r\n\r\n" to "505 HTTP Version Not Supported",
            "POST /mcp HTTP/1.1\r\nHost: h\r\n\r\n" to "411 Length Required",
            "POST /mcp HTTP/1.1\r\nHost: h\r\nContent-Length: 999999\r\n\r\n" to "413 Content Too Large",
            "POST /${"a".repeat(3000)} HTTP/1.1\r\n" to "414 URI Too Long",
            "POST /mcp HTTP/1.1\r\nHost: h\r\nExpect: 100-continue\r\nContent-Length: 0\r\n\r\n" to "417 Expectation Failed",
            "POST /mcp HTTP/1.1\r\nX: ${"a".repeat(9000)}\r\n" to "431 Request Header Fields Too Large",
        )
        for ((text, expected) in cases) assertEquals("HTTP/1.1 $expected", status(answer(text)), expected)
    }

    @Test
    fun `a dropped request is closed with no answer at all`() {
        assertEquals("", answer("POST /mcp HTTP/1.1\r\nHost: h\r\nContent-Length: 10\r\n\r\n{}"))
        assertTrue(handled.isEmpty())
    }

    @Test
    fun `the words of a refusal are JSON-escaped`() {
        val e = HttpExchange(RequestReader(clock = { 0L })) { _, _ -> Answer.refusal(401, "not \"the\" hub\\") }
        val out = ByteArrayOutputStream()
        e.run(ByteArrayInputStream(request().toByteArray()), out, peer)
        assertTrue(out.toString().endsWith("""{"error":"not \"the\" hub\\"}"""), out.toString())
    }

    @Test
    fun `a lower-case post never reaches the gate, even with no length`() {
        val text = answer("post /mcp HTTP/1.1\r\nHost: h\r\n\r\n")
        assertEquals("HTTP/1.1 405 Method Not Allowed", status(text))
        assertTrue(handled.isEmpty())
    }

    private fun answerWith(handler: Handler): String {
        val out = ByteArrayOutputStream()
        HttpExchange(RequestReader(clock = { 0L }), handler)
            .run(ByteArrayInputStream(request().toByteArray(Charsets.ISO_8859_1)), out, peer)
        return out.toString(Charsets.ISO_8859_1)
    }

    @Test
    fun `a handler that throws is answered 500 with an error body`() {
        val text = answerWith { _, _ -> throw IllegalStateException("broken") }
        assertEquals("HTTP/1.1 500 Internal Server Error", status(text))
        assertTrue(text.endsWith("\r\n\r\n{\"error\":\"the phone could not answer this request\"}"), text)
        assertTrue(!text.contains("broken"), text)
    }

    @Test
    fun `a handler's header never doubles a framing header, in any case`() {
        val text = answerWith { _, _ ->
            Answer(200, "{}".toByteArray(), mapOf("content-length" to "99", "CONNECTION" to "keep-alive", "X-Ok" to "1"))
        }
        val names = text.substringBefore("\r\n\r\n").split("\r\n").drop(1).map { it.substringBefore(':') }
        assertEquals(listOf("X-Ok", "Content-Type", "Content-Length", "Connection"), names, text)
        assertTrue(text.contains("\r\nContent-Length: 2\r\n"), text)
    }

    @Test
    fun `a CR or LF in a handler's header is never written, and the answer is 500`() {
        for (bad in listOf("X-A" to "1\r\nSet-Cookie: x", "X-A\nB" to "1", "X-A" to "1\rx")) {
            val text = answerWith { _, _ -> Answer(200, "{}".toByteArray(), mapOf(bad)) }
            assertEquals("HTTP/1.1 500 Internal Server Error", status(text), bad.toString())
            assertTrue(!text.contains("Set-Cookie") && !text.contains("X-A"), text)
        }
    }
}
