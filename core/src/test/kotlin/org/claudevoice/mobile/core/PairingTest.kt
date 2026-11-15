package org.claudevoice.mobile.core

import java.net.InetAddress
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Base64
import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PairingTest {
    private var now = Instant.parse("2026-11-15T09:00:00Z")
    private val hub = InetAddress.getByName("100.64.0.1")
    private val pairing = Pairing(clock = { now }, random = Random(7))

    private fun pair(code: String, from: InetAddress = hub) = pairing.pair(code, from) { "laptop-a" }

    private fun refused(result: PairResult) = result as PairResult.Refused

    @Test
    fun `a code is eight characters that cannot be misheard`() {
        repeat(50) {
            val code = pairing.newCode()
            assertTrue(Regex("^[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{8}$").matches(code), code)
        }
    }

    @Test
    fun `the right code in time gives a long random key and the hub's name`() {
        val paired = pair(pairing.newCode()) as PairResult.Paired
        assertTrue(Regex("^[A-Za-z0-9_-]{43,}$").matches(paired.key), paired.key)
        assertEquals(32, Base64.getUrlDecoder().decode(paired.key).size)
        assertEquals("laptop-a", paired.hubName)
        assertEquals(hub, paired.hubAddress)
    }

    @Test
    fun `only a hash of the key is given to keep`() {
        val paired = pair(pairing.newCode()) as PairResult.Paired
        val expected = MessageDigest.getInstance("SHA-256").digest(paired.key.toByteArray())
        assertTrue(expected.contentEquals(paired.keyHash))
        assertEquals("Paired(hub=laptop-a at 100.64.0.1)", paired.toString(), "the key must not show in logs")
    }

    @Test
    fun `each pairing gives a new key`() {
        val first = pair(pairing.newCode()) as PairResult.Paired
        val second = pair(pairing.newCode()) as PairResult.Paired
        assertNotEquals(first.key, second.key)
    }

    @Test
    fun `a code works once`() {
        val code = pairing.newCode()
        pair(code)
        assertEquals("wrong_code", refused(pair(code)).code)
    }

    @Test
    fun `a code works for ten minutes and no longer`() {
        val code = pairing.newCode()
        now += Duration.ofMinutes(10)
        assertEquals("wrong_code", refused(pair(code)).code)
        val fresh = pairing.newCode()
        now += Duration.ofMinutes(10).minusSeconds(1)
        assertTrue(pair(fresh) is PairResult.Paired)
    }

    @Test
    fun `a new code ends the old one`() {
        val old = pairing.newCode()
        val new = pairing.newCode()
        assertEquals("wrong_code", refused(pair(old)).code)
        assertTrue(pair(new) is PairResult.Paired)
    }

    @Test
    fun `five wrong codes end the pairing, even the right one after`() {
        val code = pairing.newCode()
        repeat(5) { assertEquals("wrong_code", refused(pair("AAAAAAAA")).code) }
        assertEquals("wrong_code", refused(pair(code)).code)
    }

    @Test
    fun `four wrong codes still leave the right one working`() {
        val code = pairing.newCode()
        repeat(4) { pair("AAAAAAAA") }
        assertTrue(pair(code) is PairResult.Paired)
    }

    @Test
    fun `with no code shown, every code is wrong`() {
        assertEquals("wrong_code", refused(pair("K7MP2QXR")).code)
    }

    @Test
    fun `a caller off the tailnet is refused before the code is tried`() {
        val code = pairing.newCode()
        val r = refused(pair(code, from = InetAddress.getByName("192.0.2.10")))
        assertEquals("not_on_tailnet", r.code)
        assertEquals(403, r.status)
        assertTrue(pair(code) is PairResult.Paired, "a caller off the tailnet does not use up the code")
    }

    @Test
    fun `a wrong code is refused with 403`() {
        pairing.newCode()
        assertEquals(403, refused(pair("AAAAAAAA")).status)
    }

    @Test
    fun `a hub whose name cannot be found is refused, and the code stays`() {
        val code = pairing.newCode()
        val r = refused(pairing.pair(code, hub) { null })
        assertEquals("not_on_tailnet", r.code)
        assertTrue(pair(code) is PairResult.Paired)
    }
}
