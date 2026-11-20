package org.claudevoice.mobile.plugins.sms.rules

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** One inbox for the whole app: the receiver and the tool must share its lock and its file. */
class SharedTest {
    @Test
    fun `the first ask makes it, and every later ask gets the same one`() {
        val made = AtomicInteger()
        val shared = Shared<Any>()
        val first = shared.get { made.incrementAndGet(); Any() }
        val second = shared.get { made.incrementAndGet(); Any() }
        assertSame(first, second)
        assertEquals(1, made.get())
    }

    @Test
    fun `two threads asking at once still get one, made once`() {
        val made = AtomicInteger()
        val shared = Shared<Any>()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val asks = List(2) {
            pool.submit<Any> {
                start.await()
                shared.get {
                    made.incrementAndGet()
                    Thread.sleep(50)
                    Any()
                }
            }
        }
        start.countDown()
        val got = asks.map { it.get(5, TimeUnit.SECONDS) }
        pool.shutdown()
        assertSame(got[0], got[1])
        assertEquals(1, made.get())
    }
}
