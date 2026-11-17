package org.claudevoice.mobile.core

import java.io.File
import java.io.IOException
import java.net.InetAddress
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.assertThrows

class FileTextStoreTest {
    /** A folder of its own for each test, which JUnit deletes afterwards. */
    @TempDir
    lateinit var dir: File

    private val file get() = File(dir, "hub.json")

    @Test
    fun `no file yet is no hub`() {
        assertNull(FileTextStore(file).read())
    }

    @Test
    fun `what is written is read back after a restart, and no new file is left behind`() {
        FileTextStore(file).write("""{"name":"hub-a"}""")
        assertEquals("""{"name":"hub-a"}""", FileTextStore(file).read())
        assertEquals(listOf("hub.json"), dir.list()!!.toList())
    }

    @Test
    fun `a new pairing replaces the old one whole`() {
        val store = FileTextStore(file)
        store.write("""{"name":"hub-a","address":"100.64.0.1"}""")
        store.write("""{"name":"hub-b"}""")
        assertEquals("""{"name":"hub-b"}""", store.read())
    }

    @Test
    fun `a write that cannot be put in place says so, and the hub before is kept`() {
        val store = FileTextStore(file)
        store.write("""{"name":"hub-a"}""")
        File(dir, "hub.json.new").apply { mkdir(); File(this, "x").writeText("") }
        assertThrows<IOException> { store.write("""{"name":"hub-b"}""") }
        assertEquals("""{"name":"hub-a"}""", store.read())
    }

    @Test
    fun `the gate built on it still knows its hub after a restart`() {
        val phone = InetAddress.getByName("100.64.0.2")
        val first = KeptGate(FileTextStore(file), own = phone, port = 8812, machine = "phone-a")
        val hub = InetAddress.getByName("100.64.0.1")
        check(first.paired(PairResult.Paired(keyHash = ByteArray(32) { 1 }, hubName = "hub-a", hubAddress = hub, key = "k")))
        val again = KeptGate(FileTextStore(file), own = phone, port = 8812, machine = "phone-a")
        assertEquals("hub-a", again.hubName)
        assertEquals(hub, again.hubAddress)
        assertNull(again.problem)
    }
}
