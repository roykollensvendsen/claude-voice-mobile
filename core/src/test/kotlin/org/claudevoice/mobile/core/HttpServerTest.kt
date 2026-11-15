package org.claudevoice.mobile.core

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The server of ADR-CVM-005 on real sockets, on loopback through the internal constructor. */
class HttpServerTest {
    private val loopback = InetAddress.getByName("127.0.0.1")
    private val clock = AtomicLong(0)
    private val handled = CopyOnWriteArrayList<String>()
    private var admitted: (InetAddress) -> Boolean = { true }
    private var body = """{"ok":true}""".toByteArray()
    private lateinit var server: HttpServer

    private fun start(readTimeoutMillis: Int = 50, writeLimitMillis: Long = 10_000) {
        val exchange = HttpExchange(RequestReader(clock = { clock.get() })) { request, _ ->
            handled += request.target
            Answer(200, body)
        }
        server = HttpServer(
            ServerSocket(0, 50, loopback), exchange, clock = { clock.get() }, admit = { admitted(it) },
            readTimeoutMillis = readTimeoutMillis, writeLimitMillis = writeLimitMillis,
        )
        server.start()
    }

    @AfterEach
    fun stop() = server.stop()

    private fun connect() = Socket(loopback, server.port).apply { soTimeout = 5000 }

    private val request = "POST /mcp HTTP/1.1\r\nHost: h\r\nContent-Length: 2\r\n\r\n{}".toByteArray()

    private fun Socket.answer(): String = getInputStream().readBytes().toString(Charsets.ISO_8859_1)

    private fun eventually(what: String, check: () -> Boolean) {
        val until = System.nanoTime() + 5_000_000_000L
        while (!check()) {
            if (System.nanoTime() > until) throw AssertionError(what)
            Thread.sleep(10)
        }
    }

    @Test
    fun `a request over a socket is answered, and the connection closed`() {
        start()
        connect().use { s ->
            s.getOutputStream().write(request)
            val text = s.answer()
            assertTrue(text.startsWith("HTTP/1.1 200 OK\r\n"), text)
            assertTrue(text.endsWith("""{"ok":true}"""), text)
        }
        assertEquals(listOf("/mcp"), handled)
    }

    @Test
    fun `a fifth connection at once is closed at once`() {
        start(readTimeoutMillis = 1000)
        val held = List(4) { connect() }
        eventually("four open") { server.open == 4 }
        connect().use { fifth -> assertEquals(-1, fifth.getInputStream().read()) }
        assertEquals(4, server.open)
        held.forEach { it.close() }
    }

    @Test
    fun `a peer not admitted is closed before anything is read`() {
        admitted = { false }
        start()
        connect().use { s ->
            s.getOutputStream().write(request)
            assertEquals("", s.answer())
        }
        assertTrue(handled.isEmpty())
    }

    @Test
    fun `a silent client is closed at the head limit`() {
        start(readTimeoutMillis = 20)
        connect().use { s ->
            eventually("open") { server.open == 1 }
            clock.addAndGet(5_000_000_001L)
            assertEquals(-1, s.getInputStream().read())
        }
        eventually("closed") { server.open == 0 }
    }

    @Test
    fun `stopping closes the listening socket and every open connection`() {
        start(readTimeoutMillis = 1000)
        val idle = connect()
        eventually("open") { server.open == 1 }
        server.stop()
        assertEquals(-1, idle.getInputStream().read())
        // Not a new connect: another process may be handed the freed port at once.
        assertEquals(false, server.listening)
        eventually("the connection is gone") { server.open == 0 }
        idle.close()
    }

    @Test
    fun `an answer the client will not read is cut off at the write limit`() {
        body = ByteArray(64 * 1024 * 1024) { 'a'.code.toByte() }
        start(writeLimitMillis = 200)
        connect().use { s ->
            s.receiveBufferSize = 4096
            s.getOutputStream().write(request)
            // First seen open, or "none open" passes before the server has even accepted it.
            eventually("accepted") { server.open == 1 }
            eventually("the server gave up writing") { server.open == 0 }
        }
    }

    @Test
    fun `the opener binds the address it is given, and starts serving`() {
        start()
        val asked = mutableListOf<Pair<InetAddress, Int>>()
        val opener = HttpServerOpener(
            HttpExchange(RequestReader(clock = { 0L })) { _, _ -> Answer(200, body) },
            clock = { 0L },
            admit = { true },
            bind = { address, port -> asked += address to port; ServerSocket(0, 50, loopback) },
        )
        val tailnet = checkNotNull(BindAddress.of(InetAddress.getByName("100.64.0.2")))
        val opened = opener.open(tailnet, 8812) as HttpServer
        try {
            assertEquals(listOf(InetAddress.getByName("100.64.0.2") to 8812), asked)
            Socket(loopback, opened.port).use { s ->
                s.soTimeout = 5000
                s.getOutputStream().write(request)
                assertTrue(s.answer().startsWith("HTTP/1.1 200 OK"))
            }
        } finally {
            opened.stop()
        }
    }

    @Test
    fun `the real opener binds the tailnet address itself, and a missing one fails as IOException`() {
        start()
        val opener = HttpServerOpener(HttpExchange(RequestReader(clock = { 0L })) { _, _ -> Answer(200, body) }, { 0L }, { true })
        // 100.64.0.0 is the range's network address, which no machine is given.
        val absent = checkNotNull(BindAddress.of(InetAddress.getByName("100.64.0.0")))
        assertThrows(java.io.IOException::class.java) { opener.open(absent, 0) }
    }

    @Test
    fun `the real bind listens on exactly the address it is given`() {
        start()
        listenOn(loopback, 0).use { socket ->
            assertEquals(loopback, socket.inetAddress)
            assertTrue(socket.localPort > 0)
        }
    }

    @Test
    fun `a connection accepted just as the server stops is closed, not served`() {
        admitted = { server.stop(); true }
        start(readTimeoutMillis = 20)
        connect().use { s ->
            s.getOutputStream().write(request)
            assertEquals("", s.answer())
        }
        assertEquals(0, server.open)
        assertTrue(handled.isEmpty())
    }

    @Test
    fun `stopping while an answer is being written leaves no thread failing`() {
        val failures = CopyOnWriteArrayList<Throwable>()
        val before = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> failures += e }
        try {
            val exchange = HttpExchange(RequestReader(clock = { clock.get() })) { _, _ ->
                server.stop()
                Answer(200, body)
            }
            server = HttpServer(ServerSocket(0, 50, loopback), exchange, { clock.get() }, { true }, 50, 10_000)
            server.start()
            connect().use { s ->
                s.getOutputStream().write(request)
                s.answer()
            }
            eventually("the connection is gone") { server.open == 0 }
            Thread.sleep(100)
            assertEquals(emptyList<Throwable>(), failures.toList())
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(before)
        }
    }

    @Test
    fun `a passing accept failure, such as too many open files, does not end the server`() {
        val exchange = HttpExchange(RequestReader(clock = { clock.get() })) { request, _ ->
            handled += request.target
            Answer(200, body)
        }
        val flaky = object : ServerSocket(0, 50, loopback) {
            var failed = false

            override fun accept(): Socket {
                if (!failed) {
                    failed = true
                    throw java.io.IOException("Too many open files")
                }
                return super.accept()
            }
        }
        server = HttpServer(flaky, exchange, { clock.get() }, { true }, 50, 10_000)
        server.start()
        eventually("the failure was seen") { flaky.failed }
        connect().use { s ->
            s.getOutputStream().write(request)
            assertTrue(s.answer().startsWith("HTTP/1.1 200 OK"))
        }
    }

    @Test
    fun `an accept that keeps failing is retried after a pause, not in a busy loop`() {
        val exchange = HttpExchange(RequestReader(clock = { clock.get() })) { _, _ -> Answer(200, body) }
        val tries = AtomicLong(0)
        val failing = object : ServerSocket(0, 50, loopback) {
            override fun accept(): Socket {
                tries.incrementAndGet()
                throw java.io.IOException("Too many open files")
            }
        }
        server = HttpServer(failing, exchange, { clock.get() }, { true }, 50, 10_000)
        server.start()
        Thread.sleep(500)
        // A pause of 100 ms allows about five tries in half a second; a busy loop makes thousands.
        assertTrue(tries.get() in 2..10, "${tries.get()} tries")
    }
}
