package org.claudevoice.mobile.plugins.sms.rules

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Instant
import java.time.OffsetDateTime

/**
 * `sms_since` answers exactly as `docs/plugins/sms/sms-since-answer.json` says (ADR-CVM-010).
 * Every sender, number and text here is made up.
 */
class SmsSinceTest {
    private val answerSchema = SchemaCheck.schema("sms-since-answer.json")
    private val inputSchema = SchemaCheck.schema("sms-since-input.json")

    private val at = OffsetDateTime.parse("2026-11-20T08:00:00+01:00")

    private fun kept(
        id: String = "inst0001-1",
        from: String = "+4700000021",
        kind: Senders.Kind = Senders.Kind.NUMBER,
        text: String = "Ses vi klokka seks?",
        cut: Boolean = false,
        masked: Boolean = false,
        when_: OffsetDateTime = at,
    ) = Kept(id, from, kind, text, cut, masked, when_)

    private fun fits(answer: JsonObject) = assertEquals(listOf<String>(), SchemaCheck.errors(answer, answerSchema))

    private fun JsonObject.message(i: Int = 0) = this["messages"]!!.jsonArray[i].jsonObject

    private fun JsonObject.string(key: String) = this[key]!!.jsonPrimitive.content

    @Test
    fun `an answer gives each message with every field the schema names, and fits it`() {
        val answer = SmsSince.answer(Batch(listOf(kept()), "C00000000001", false, 0, false))
        fits(answer)
        val message = answer.message()
        assertEquals("inst0001-1", message.string("id"))
        assertEquals("+4700000021", message.string("from"))
        assertEquals("number", message.string("from_kind"))
        assertEquals("2026-11-20T08:00:00+01:00", message.string("received_at"))
        assertEquals("Ses vi klokka seks?", message.string("text"))
        assertEquals(JsonPrimitive(false), message["cut"])
        assertEquals(JsonPrimitive(false), message["masked"])
        assertEquals(JsonPrimitive(true), message["untrusted"])
        assertEquals("C00000000001", answer.string("cursor"))
    }

    @Test
    fun `the answer carries more, dropped and reset as the batch has them`() {
        val answer = SmsSince.answer(Batch(listOf(kept()), "C00000000002", true, 3, true))
        fits(answer)
        assertEquals(JsonPrimitive(true), answer["more"])
        assertEquals(JsonPrimitive(3), answer["dropped"])
        assertEquals(JsonPrimitive(true), answer["reset"])
        assertEquals(JsonPrimitive(false), SmsSince.answer(Batch(listOf(), "C1", false, 0, false))["reset"])
    }

    @Test
    fun `an empty answer fits too`() {
        val answer = SmsSince.answer(Batch(listOf(), "C00000000003", false, 0, false))
        fits(answer)
        assertEquals(JsonArray(listOf()), answer["messages"])
    }

    @Test
    fun `a hidden, a cut, a short-code and a named message each fit, with their flags`() {
        val answer = SmsSince.answer(
            Batch(
                listOf(
                    kept(id = "inst0001-1", from = "Eksempelbank", kind = Senders.Kind.NAME, text = "a one-time code from Eksempelbank", masked = true),
                    kept(id = "inst0001-2", text = "a".repeat(SmsQueue.LONGEST_TEXT), cut = true),
                    kept(id = "inst0001-3", from = "2244", kind = Senders.Kind.SHORT_CODE),
                ),
                "C00000000004",
                false,
                0,
                false,
            ),
        )
        fits(answer)
        assertEquals(JsonPrimitive(true), answer.message(0)["masked"])
        assertEquals("name", answer.message(0).string("from_kind"))
        assertEquals(JsonPrimitive(true), answer.message(1)["cut"])
        assertEquals("short_code", answer.message(2).string("from_kind"))
    }

    @Test
    fun `a time is given with its seconds and its offset, even when both are zero`() {
        val utc = OffsetDateTime.parse("2026-11-20T07:00Z")
        assertEquals("2026-11-20T07:00:00+00:00", SmsSince.answer(Batch(listOf(kept(when_ = utc)), "C1", false, 0, false)).message().string("received_at"))
        val odd = OffsetDateTime.parse("2026-11-20T09:15:30.250-03:30")
        val answer = SmsSince.answer(Batch(listOf(kept(when_ = odd)), "C1", false, 0, false))
        fits(answer)
        assertEquals("2026-11-20T09:15:30-03:30", answer.message().string("received_at"))
    }

    @Test
    fun `the checker refuses an answer that breaks the schema, so it cannot pass everything`() {
        val good = SmsSince.answer(Batch(listOf(kept()), "C1", false, 0, false))
        fun broken(change: (MutableMap<String, kotlinx.serialization.json.JsonElement>) -> Unit): JsonObject {
            val message = good.message().toMutableMap().also(change)
            return JsonObject(good + ("messages" to JsonArray(listOf(JsonObject(message)))))
        }
        val wrong = listOf(
            JsonObject(good - "cursor"),
            JsonObject(good + ("extra" to JsonPrimitive(1))),
            JsonObject(good + ("dropped" to JsonPrimitive(-1))),
            JsonObject(good + ("more" to JsonPrimitive("no"))),
            broken { it["id"] = JsonPrimitive("INST-1") },
            broken { it["text"] = JsonPrimitive("a".repeat(SmsQueue.LONGEST_TEXT + 1)) },
            broken { it["untrusted"] = JsonPrimitive(false) },
            broken { it["from_kind"] = JsonPrimitive("email") },
            broken { it["from"] = JsonPrimitive("") },
            broken { it["received_at"] = JsonPrimitive("2026-11-20T08:00:00") },
            broken { it.remove("cut") },
        )
        for (answer in wrong) assertTrue(SchemaCheck.errors(answer, answerSchema).isNotEmpty(), answer.toString())
    }

    @Test
    fun `the schema's own examples fit, so the checker reads the schema as it is meant`() {
        for (example in answerSchema["examples"]!!.jsonArray) fits(example.jsonObject)
        for (example in inputSchema["examples"]!!.jsonArray) {
            assertEquals(listOf<String>(), SchemaCheck.errors(example, inputSchema))
        }
    }

    @Test
    fun `the tool's input is the input schema's own shape`() {
        fun shape(node: JsonObject): JsonObject = JsonObject(
            node.filterKeys { it !in setOf("\$schema", "\$id", "title", "description", "examples") }
                .mapValues { (_, v) -> if (v is JsonObject) shape(v) else v },
        )
        assertEquals(shape(inputSchema), SmsSince.INPUT)
    }

    @Test
    fun `no cursor, or null, starts at the oldest kept, and a string is passed on as it came`() {
        assertNull(SmsSince.cursor(JsonObject(emptyMap())))
        assertNull(SmsSince.cursor(JsonObject(mapOf("cursor" to JsonNull))))
        assertEquals("AZf04TqL7wNc", SmsSince.cursor(JsonObject(mapOf("cursor" to JsonPrimitive("AZf04TqL7wNc")))))
    }

    @Test
    fun `a cursor that is empty or not a string, or any other argument, is refused`() {
        val wrong = listOf(
            mapOf("cursor" to JsonPrimitive("")),
            mapOf("cursor" to JsonPrimitive(5)),
            mapOf("cursor" to JsonPrimitive(true)),
            mapOf("cursor" to JsonObject(emptyMap())),
            mapOf("cursor" to JsonArray(listOf())),
            mapOf("since" to JsonPrimitive("AZ")),
        )
        for (arguments in wrong) {
            val refused = assertThrows<IllegalArgumentException>(arguments.toString()) { SmsSince.cursor(JsonObject(arguments)) }
            assertTrue(refused.message!!.contains("cursor"), refused.message)
        }
    }

    @Test
    fun `a call answers from the inbox, and passing its cursor back acknowledges it`(@TempDir folder: File) {
        val inbox = Inbox(QueueFile(File(folder, "q"), plainSeal), FixedChance("inst0001")) { NOW }
        inbox.add("+4700000021", "en", at, noCountry)
        val first = SmsSince.call(inbox, JsonObject(emptyMap())) as SmsSince.Reply.Answer
        fits(first.content)
        assertEquals(1, first.content["messages"]!!.jsonArray.size)
        val cursor = first.content.string("cursor")
        val next = SmsSince.call(inbox, JsonObject(mapOf("cursor" to JsonPrimitive(cursor)))) as SmsSince.Reply.Answer
        assertEquals(0, next.content["messages"]!!.jsonArray.size)
        assertEquals(cursor, next.content.string("cursor"))
    }

    @Test
    fun `a call with wrong arguments is refused in words, and the inbox is not touched`(@TempDir folder: File) {
        val inbox = Inbox(QueueFile(File(folder, "q"), plainSeal), FixedChance("inst0001")) { NOW }
        inbox.add("+4700000021", "en", at, noCountry)
        val refused = SmsSince.call(inbox, JsonObject(mapOf("cursor" to JsonPrimitive(5))))
        assertTrue(refused is SmsSince.Reply.Refused)
        assertTrue((refused as SmsSince.Reply.Refused).say.contains("cursor"))
        val answer = SmsSince.call(inbox, Json.parseToJsonElement("{}").jsonObject) as SmsSince.Reply.Answer
        assertFalse(answer.content["messages"]!!.jsonArray.isEmpty())
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-11-20T07:00:00Z")
    }
}
