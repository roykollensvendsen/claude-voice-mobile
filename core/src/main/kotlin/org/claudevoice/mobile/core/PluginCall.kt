package org.claudevoice.mobile.core

import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlinx.serialization.json.JsonObject
import org.claudevoice.mobile.pluginapi.Plugin
import org.claudevoice.mobile.pluginapi.ToolResult

/** How one plugin call ended, as the handler answers it. */
sealed interface CallOutcome {
    data class Done(val result: ToolResult) : CallOutcome

    /** Past the limit, once started. The call cannot be cancelled, only abandoned: it may still finish. */
    data object Late : CallOutcome

    /** Past the limit while still waiting for a thread: it is cancelled, and never reaches the plugin. */
    data object NotRun : CallOutcome

    /** The plugin threw; only the exception's class is kept, never its message. */
    data class Broke(val kind: String) : CallOutcome
}

/**
 * Runs a plugin's suspending call with the Kotlin standard library alone, so the core needs no
 * coroutines library. The call starts on [CALLS], a few daemon threads of its own, and the
 * connection's thread waits at most [limit] for it. A plugin that suspends properly gives its
 * thread back while it waits; one that blocks holds it, which is why there are as many threads
 * as the server takes connections. Past [limit], a call that has started is abandoned, not
 * cancelled: a plugin call cannot be stopped from outside, so it may still finish, and the answer
 * says so. A call still waiting for a thread, behind four that block, is cancelled instead, so it
 * never runs minutes after the hub was answered (issue #85).
 */
class PluginCall(private val limit: Duration) {
    fun run(plugin: Plugin, tool: String, arguments: JsonObject): CallOutcome {
        val done = CompletableFuture<ToolResult>()
        val pending = Pending(plugin, tool, arguments)
        val finished = Continuation<ToolResult>(EmptyCoroutineContext) { done.complete(it) }
        val taken = AtomicBoolean()
        CALLS.execute { if (taken.compareAndSet(false, true)) (Pending::start).startCoroutine(pending, finished) }
        return waitFor(done, taken)
    }

    /**
     * One call, started through a reference to a named function rather than a suspending lambda:
     * a tail call in a named function has no state machine of its own, so no code the compiler
     * writes for one is left for Pitest to mutate.
     */
    private class Pending(private val plugin: Plugin, private val tool: String, private val arguments: JsonObject) {
        suspend fun start(): ToolResult = plugin.call(tool, arguments)
    }

    private fun waitFor(done: CompletableFuture<ToolResult>, taken: AtomicBoolean): CallOutcome = try {
        CallOutcome.Done(done.get(limit.toMillis(), TimeUnit.MILLISECONDS))
    } catch (_: TimeoutException) {
        abandoned(taken)
    } catch (e: ExecutionException) {
        CallOutcome.Broke((e.cause ?: e).javaClass.name)
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        abandoned(taken)
    }

    /**
     * The thread that starts the call and the waiter that gives up both try to take it, and only
     * the first wins. So a call is either run or dropped, never both. `Future.cancel` cannot tell
     * the two apart: a task stays new while it runs, so cancelling it succeeds even then.
     */
    private fun abandoned(taken: AtomicBoolean): CallOutcome =
        if (taken.compareAndSet(false, true)) CallOutcome.NotRun else CallOutcome.Late

    private fun CompletableFuture<ToolResult>.complete(result: Result<ToolResult>) {
        result.fold({ complete(it) }, { completeExceptionally(it) })
    }

    private companion object {
        /** As many as the server takes connections (ADR-CVM-005), so a call never waits to start. */
        val CALLS: ExecutorService = Executors.newFixedThreadPool(HttpServer.MAX_CONNECTIONS) { task ->
            Thread(task, "plugin-call").apply { isDaemon = true }
        }
    }
}
