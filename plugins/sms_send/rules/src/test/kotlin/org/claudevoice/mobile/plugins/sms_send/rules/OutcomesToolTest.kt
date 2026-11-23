package org.claudevoice.mobile.plugins.sms_send.rules

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.claudevoice.mobile.capabilities.cursorqueue.Chance
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * `sms_send_outcomes` (ADR-CVM-010): the hub's poll of how each text ended. It works as
 * `sms_since` does, answers even when sending was taken back, and its description carries the
 * record's sentence about lost outcomes word for word.
 */
class OutcomesToolTest {
    @TempDir
    lateinit var folder: File

    private val clock = Clock.fixed(Instant.parse("2026-11-23T19:40:00Z"), ZoneId.of("Europe/Oslo"))

    private class Counted : Chance {
        private var cursors = 0

        override fun installPart() = "p4w8c1z6"

        override fun cursor() = "Qm7Tz2pL" + (++cursors).toString().padStart(4, '0')
    }

    private val outbox = Outbox(clock, { _, _ -> }, { _ -> }, {}, { "t-1" })

    private fun journal() = Journal.open(
        PlainFile(File(folder, "send-outcomes")) {},
        PlainFile(File(folder, "send-markers")) {},
        Counted(),
        clock,
    )

    private fun call(journal: Journal?, arguments: String) =
        SmsSend.outcomes(journal, Json.parseToJsonElement(arguments).jsonObject)

    private fun answer(reply: Reply) = (reply as Reply.Answer).content

    private fun record(): String {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "decisions").isDirectory) dir = dir.parentFile
        val file = File(checkNotNull(dir), "decisions").listFiles()!!.single { it.name.startsWith("ADR-CVM-010") }
        return file.readText().split(Regex("\\s+")).joinToString(" ")
    }

    private fun shape(node: JsonObject): JsonObject = JsonObject(
        node.filterKeys { it !in setOf("\$schema", "\$id", "title", "description", "examples") }
            .mapValues { (_, v) -> if (v is JsonObject) shape(v) else v },
    )

    @Test
    fun `the tool's input is the input schema's own shape, and nothing in it is required`() {
        val tool = SmsSend.TOOLS.single { it.name == SmsSend.OUTCOMES }
        assertEquals(shape(SchemaCheck.schema("sms-send-outcomes-input.json")), tool.input)
    }

    @Test
    fun `its description carries the record's sentence about lost outcomes word for word`() {
        val words = SmsSend.TOOLS.single { it.name == SmsSend.OUTCOMES }.description
        val sentence = "When dropped is above 0, say that some outcomes were lost and that a text may have been " +
            "sent; check the text app."
        assertTrue(words.contains(sentence), words)
        assertTrue(record().contains(sentence), "the record still says it")
    }

    @Test
    fun `its answer fits the schema`() {
        val journal = journal()
        journal.held("t-1", 1)
        journal.endedAll("t-1", Reason.BY_OWNER)
        val answer = answer(call(journal, "{}"))
        assertEquals(listOf<String>(), SchemaCheck.errors(answer, SchemaCheck.schema("sms-send-outcomes-answer.json")))
        assertEquals("stopped by the owner", answer.getValue("outcomes").jsonArray.single().jsonObject.getValue("reason").jsonPrimitive.content)
    }

    @Test
    fun `the other three tools still refuse while sending is taken back`() {
        for (tool in listOf(SmsSend.REQUEST, SmsSend.CONFIRM, SmsSend.CANCEL)) {
            val reply = SmsSend.call(outbox, tool, JsonObject(emptyMap()), permitted = false)
            assertEquals(SmsSend.PERMISSION_MISSING, (reply as Reply.Refused).code, tool)
        }
    }

    @Test
    fun `passing back the cursor acknowledges the answer, and null is the same as none`() {
        val journal = journal()
        journal.held("t-1", 1)
        journal.endedAll("t-1", Reason.BY_OWNER)
        val first = answer(call(journal, """{"cursor":null}"""))
        val cursor = first.getValue("cursor").jsonPrimitive.content
        val next = answer(call(journal, """{"cursor":"$cursor"}"""))
        assertEquals(0, next.getValue("outcomes").jsonArray.size)
    }

    @Test
    fun `arguments that do not fit the input are refused, and nothing is handed out`() {
        val journal = journal()
        for (wrong in listOf("""{"cursor":""}""", """{"cursor":7}""", """{"since":"x"}""", """{"cursor":"a","x":1}""")) {
            val reply = call(journal, wrong)
            assertTrue(reply is Reply.Refused, wrong)
            assertTrue((reply as Reply.Refused).say.contains("cursor"), reply.say)
        }
    }

    @Test
    fun `an answer that cannot be saved is refused, so asking again is safe`() {
        val journal = journal()
        journal.held("t-1", 1)
        journal.endedAll("t-1", Reason.BY_OWNER)
        File(folder, "send-outcomes.new").mkdir()
        val reply = call(journal, "{}")
        assertEquals(Reply.Refused(SmsSend.NOT_SAVED), reply)
    }

    @Test
    fun `without its journal the tool says the phone keeps no outcomes`() {
        val reply = call(null, "{}")
        assertTrue(reply is Reply.Refused)
    }
}
