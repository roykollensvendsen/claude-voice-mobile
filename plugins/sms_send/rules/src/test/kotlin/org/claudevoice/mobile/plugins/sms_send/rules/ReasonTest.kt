package org.claudevoice.mobile.plugins.sms_send.rules

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Each outcome of a sent text says only its own phrases (ADR-CVM-010, "Delivered"). The answer's
 * schema accepts any phrase with any outcome, so the type pairs them, and this walks every pair.
 */
class ReasonTest {
    // ADR-CVM-010's list, word for word: which phrases belong to which outcome.
    private val adr = mapOf(
        "sent" to listOf("handed to the network"),
        "delivered" to listOf("the network reported it delivered"),
        "stopped" to listOf("stopped by the owner", "the app restarted while it was held"),
        "failed" to listOf(
            "no service",
            "radio off",
            "no default subscription",
            "refused by Android",
            "the network reported it failed",
        ),
        "unknown" to listOf("it may have been sent; check the text app"),
    )

    @Test
    fun `every reason belongs to the outcome ADR-CVM-010 lists it under, and to no other`() {
        val pairs = Reason.entries.groupBy({ it.outcome.word }, { it.phrase })
        assertEquals(adr, pairs)
    }

    @Test
    fun `every outcome has at least one reason`() {
        assertEquals(Outcome.entries.toSet(), Reason.entries.map { it.outcome }.toSet())
    }

    @Test
    fun `the words are exactly the schema's, both ways`() {
        val outcome = item()["properties"]!!.jsonObject
        val words = outcome["outcome"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }
        val phrases = outcome["reason"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(words, Outcome.entries.map { it.word })
        assertEquals(phrases, Reason.entries.map { it.phrase })
    }

    @Test
    fun `no two reasons share a phrase`() {
        assertEquals(Reason.entries.size, Reason.entries.map { it.phrase }.toSet().size)
    }

    private fun item(): JsonObject {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, SCHEMA).isFile) dir = dir.parentFile
        val file = File(checkNotNull(dir) { "no $SCHEMA above the test's folder" }, SCHEMA)
        return Json.parseToJsonElement(file.readText()).jsonObject["\$defs"]!!.jsonObject["outcome"]!!.jsonObject
    }

    private companion object {
        const val SCHEMA = "docs/plugins/sms/sms-send-outcomes-answer.json"
    }
}
