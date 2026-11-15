package org.claudevoice.mobile.core

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * The phone's HTTP server, as ADR-CVM-005 sets. It accepts on one socket, and for each connection:
 * - closes it at once when [admit] says no (once paired, a peer other than the hub's address), or
 *   when four connections are already open;
 * - gives it a short read timeout, so a silent client wakes the reader, which drops it at its
 *   deadline counted from the accept;
 * - answers one request through [exchange], with a watchdog that closes the socket when writing
 *   takes longer than the write limit, and then closes it.
 *
 * [stop] closes the listening socket and every open connection.
 *
 * [admit] runs on the one accept thread, before anything is read: it must stay cheap, such as a
 * comparison with the paired hub's address, and never look anything up. The [exchange]'s handler
 * runs on the connection's thread and must bound its own time: the server's deadlines cover only
 * reading the request and writing the answer.
 */
class HttpServer internal constructor(
    private val socket: ServerSocket,
    private val exchange: HttpExchange,
    private val clock: () -> Long,
    private val admit: (InetAddress) -> Boolean,
    private val readTimeoutMillis: Int = READ_TIMEOUT_MILLIS,
    private val writeLimitMillis: Long = WRITE_LIMIT_MILLIS,
) : Server {
    private val connections: MutableSet<Socket> = Collections.synchronizedSet(mutableSetOf())
    private val watchdog = Executors.newSingleThreadScheduledExecutor()

    val port: Int get() = socket.localPort

    val address: InetAddress get() = socket.inetAddress

    /** Whether the listening socket is still open. */
    internal val listening: Boolean get() = !socket.isClosed

    /** Connections open now. */
    val open: Int get() = connections.size

    fun start() {
        Thread(::accept, "http-accept").start()
    }

    /**
     * The listening socket closes under the same lock [admitted] counts under, so a connection
     * accepted at that moment is either in the snapshot closed here or refused there.
     */
    override fun stop() {
        synchronized(connections) {
            closeQuietly(socket)
            connections.toList()
        }.forEach(::closeQuietly)
        watchdog.shutdownNow()
    }

    private fun accept() {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (_: IOException) {
                // Closed by stop(), which ends the loop; or a passing failure, such as too many
                // open files, after which the server keeps listening a moment later.
                pause()
                continue
            }
            val acceptedAt = clock()
            if (!admit(client.inetAddress) || !admitted(client)) {
                closeQuietly(client)
            } else {
                Thread({ serve(client, acceptedAt) }, "http-connection").start()
            }
        }
    }

    /** Nothing interrupts the accept thread; [stop] ends it by closing the socket. */
    private fun pause() = Thread.sleep(ACCEPT_PAUSE_MILLIS)

    /** Count [client] in, unless the server has stopped or the limit is reached. */
    private fun admitted(client: Socket): Boolean = synchronized(connections) {
        !socket.isClosed && connections.size < MAX_CONNECTIONS && connections.add(client)
    }

    private fun serve(client: Socket, acceptedAt: Long) {
        var cutOff: ScheduledFuture<*>? = null
        try {
            client.soTimeout = readTimeoutMillis
            val output = BufferedOutputStream(client.getOutputStream())
            exchange.run(BufferedInputStream(client.getInputStream()), output, client.inetAddress, acceptedAt) {
                cutOff = watchdog.schedule({ closeQuietly(client) }, writeLimitMillis, TimeUnit.MILLISECONDS)
            }
        } catch (_: IOException) {
            // A peer gone, or the watchdog closed it: nothing more to do than close.
        } catch (_: RejectedExecutionException) {
            // The server stopped as the answer began: the watchdog is gone, so just close.
        } finally {
            cutOff?.cancel(false)
            closeQuietly(client)
            connections.remove(client)
        }
    }

    private fun closeQuietly(closeable: java.io.Closeable) {
        try {
            closeable.close()
        } catch (_: IOException) {
            // Already closed.
        }
    }

    internal companion object {
        const val MAX_CONNECTIONS = 4
        const val READ_TIMEOUT_MILLIS = 1000
        const val WRITE_LIMIT_MILLIS = 10_000L
        const val BACKLOG = 50
        const val ACCEPT_PAUSE_MILLIS = 100L
    }
}

/**
 * Opens an [HttpServer] on a [BindAddress]: a tailnet IPv4 address, nothing else. The read
 * deadlines and the write watchdog read [clock], `System.nanoTime` in the app.
 */
class HttpServerOpener internal constructor(
    private val exchange: HttpExchange,
    private val clock: () -> Long,
    private val admit: (InetAddress) -> Boolean,
    private val bind: ((InetAddress, Int) -> ServerSocket)?,
) : ServerOpener {
    constructor(exchange: HttpExchange, clock: () -> Long, admit: (InetAddress) -> Boolean) :
        this(exchange, clock, admit, null)

    override fun open(address: BindAddress, port: Int): Server {
        val socket = bind?.invoke(address.address, port) ?: listenOn(address.address, port)
        return HttpServer(socket, exchange, clock, admit).also { it.start() }
    }
}

/** A listening socket on exactly [address] and [port], never the wildcard address. */
internal fun listenOn(address: InetAddress, port: Int): ServerSocket = ServerSocket(port, HttpServer.BACKLOG, address)
