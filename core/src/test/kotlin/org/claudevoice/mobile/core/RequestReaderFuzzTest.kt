package org.claudevoice.mobile.core

import java.io.ByteArrayInputStream
import java.time.Duration
import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

/**
 * The fuzz test of ADR-CVM-005: valid and hostile requests, mutated by flipping, inserting,
 * cutting and repeating bytes. For every input the reader gives a request within the limits,
 * one of the fixed refusals, or a drop; never another exception, and never a hang.
 *
 * The seed is fixed in CI. The weekly run sets `FUZZ_SEED` and `FUZZ_CASES`, and the seed is
 * printed, so a failure can be run again exactly. A case it finds joins RequestReaderTest.
 */
class RequestReaderFuzzTest {
    private val seeds = listOf(
        "POST /mcp HTTP/1.1\r\nHost: 100.64.0.2:8812\r\nContent-Length: 2\r\n\r\n{}",
        "POST /pair HTTP/1.1\r\nHost: h\r\nAuthorization: Bearer k\r\nClaude-Voice-Protocol: 1\r\nContent-Length: 4\r\n\r\nabcd",
        "GET /mcp HTTP/1.1\r\nHost: h\r\n\r\n",
        "POST /mcp HTTP/1.1\r\nHost: h\r\nContent-Length: 2\r\nContent-Length: 2\r\n\r\n{}",
        "POST /mcp HTTP/1.1\r\nHost: h\r\nTransfer-Encoding: chunked\r\n\r\n2\r\n{}\r\n0\r\n\r\n",
        "POST /mcp HTTP/1.1\nHost: h\nContent-Length: 2\n\n{}",
        "POST /mcp HTTP/1.1\r\nHost: h\r\nX-A: 1\r\n folded\r\nContent-Length: 0\r\n\r\n",
        "POST /mcp HTTP/1.1\r\nHost: h\r\nContent-Length: 99999999999999999999\r\n\r\n",
        "POST /mcp HTTP/1.1\r\nHost: h\r\nExpect: 100-continue\r\nContent-Length: 0\r\n\r\n",
    ).map { it.toByteArray(Charsets.ISO_8859_1) }

    private val statuses = setOf(400, 411, 413, 414, 417, 431, 505)

    @Test
    fun `mutated requests give a request within the limits, a fixed refusal or a drop`() {
        val seed = System.getenv("FUZZ_SEED")?.toLongOrNull() ?: FIXED_SEED
        val cases = System.getenv("FUZZ_CASES")?.toIntOrNull() ?: CASES
        println("fuzz seed $seed, $cases cases")
        val random = Random(seed)
        assertTimeoutPreemptively(Duration.ofSeconds(60)) {
            repeat(cases) { n ->
                val input = mutate(seeds[random.nextInt(seeds.size)], random)
                val result = try {
                    RequestReader(clock = { 0L }).read(ByteArrayInputStream(input))
                } catch (e: Exception) {
                    fail("case $n (seed $seed) threw $e on ${show(input)}")
                }
                check(result, n, seed, input)
            }
        }
    }

    private fun check(result: ReadResult, n: Int, seed: Long, input: ByteArray) {
        val where = "case $n (seed $seed): ${show(input)}"
        when (result) {
            is ReadResult.Request -> {
                assertTrue(result.target.length <= 2048 && result.target.all { it in '!'..'~' }, where)
                assertTrue(result.body.size <= 262_144, where)
                assertTrue(result.headers().size <= 64 && result.header("host") != null, where)
                assertTrue(result.header("transfer-encoding") == null && result.header("expect") == null, where)
            }
            is ReadResult.Refused -> assertTrue(result.status in statuses && result.error.isNotBlank(), where)
            ReadResult.Dropped -> Unit
        }
    }

    private fun mutate(start: ByteArray, random: Random): ByteArray {
        var bytes = start
        repeat(1 + random.nextInt(4)) {
            val at = random.nextInt(bytes.size + 1)
            bytes = when (random.nextInt(4)) {
                0 -> bytes.copyOf().also { if (at < it.size) it[at] = (it[at].toInt() xor (1 shl random.nextInt(8))).toByte() }
                1 -> bytes.copyOfRange(0, at) + byteArrayOf(interesting(random)) + bytes.copyOfRange(at, bytes.size)
                2 -> bytes.copyOfRange(0, at) + bytes.copyOfRange(minOf(bytes.size, at + 1 + random.nextInt(8)), bytes.size)
                else -> {
                    val end = minOf(bytes.size, at + 1 + random.nextInt(16))
                    bytes.copyOfRange(0, end) + bytes.copyOfRange(at, end) + bytes.copyOfRange(end, bytes.size)
                }
            }
        }
        return bytes
    }

    private fun interesting(random: Random): Byte =
        INTERESTING[random.nextInt(INTERESTING.length)].code.toByte().takeIf { random.nextBoolean() }
            ?: random.nextInt(256).toByte()

    private fun show(bytes: ByteArray) = bytes.joinToString("") { b ->
        val c = b.toInt() and 0xff
        if (c in 0x20..0x7e) c.toChar().toString() else "\\x%02x".format(c)
    }

    private companion object {
        const val FIXED_SEED = 20261115L
        const val CASES = 5000
        const val INTERESTING = "\r\n :\t\u0000\u007fÿ0123456789"
    }
}
