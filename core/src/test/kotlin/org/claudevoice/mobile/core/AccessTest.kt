package org.claudevoice.mobile.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.claudevoice.mobile.pluginapi.Plugin
import org.claudevoice.mobile.pluginapi.Tool
import org.claudevoice.mobile.pluginapi.ToolResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class AccessTest {
    /** What Android says, set by each test: granted, and asked before by this install. */
    private class Phone(var granted: Set<String> = setOf(), var asked: Set<String> = setOf()) : Permissions {
        override fun granted(permission: String) = permission in granted

        override fun asked(permission: String) = permission in asked
    }

    private companion object {
        const val RECEIVE = "android.permission.RECEIVE_SMS"
        const val READ = "android.permission.READ_SMS"
        const val CONTACTS = "android.permission.READ_CONTACTS"
    }

    @Test
    fun `a use is granted only when every permission behind it is`() {
        assertEquals(Access.GRANTED, Access.of(listOf(RECEIVE, READ), Phone(granted = setOf(RECEIVE, READ))))
        assertEquals(Access.NEVER_ASKED, Access.of(listOf(RECEIVE, READ), Phone(granted = setOf(RECEIVE))))
    }

    @Test
    fun `one permission not granted after the app asked for it makes the use refused`() {
        val phone = Phone(granted = setOf(RECEIVE), asked = setOf(READ))
        assertEquals(Access.REFUSED, Access.of(listOf(RECEIVE, READ), phone))
    }

    @Test
    fun `a permission not granted and never asked for is never_asked, even when another was asked`() {
        assertEquals(Access.NEVER_ASKED, Access.of(listOf(CONTACTS), Phone()))
        assertEquals(Access.NEVER_ASKED, Access.of(listOf(CONTACTS), Phone(asked = setOf(READ))))
    }

    @Test
    fun `a granted permission that was asked for is still granted`() {
        assertEquals(Access.GRANTED, Access.of(listOf(CONTACTS), Phone(granted = setOf(CONTACTS), asked = setOf(CONTACTS))))
    }

    @Test
    fun `a refusal anywhere outweighs a permission never asked for`() {
        val phone = Phone(asked = setOf(READ))
        assertEquals(Access.REFUSED, Access.of(listOf(CONTACTS, READ), phone))
        assertEquals(Access.REFUSED, Access.of(listOf(READ, CONTACTS), phone))
    }

    @Test
    fun `a use with no Android permission behind it is granted`() {
        assertEquals(Access.GRANTED, Access.of(listOf(), Phone()))
    }

    private fun plugin(uses: List<String>, needs: Map<String, List<String>>) = object : Plugin {
        override val name = "texts"
        override val tools = listOf(Tool("texts_read", "", JsonObject(mapOf())))
        override val uses = uses
        override val needs = needs
        override val confirms = listOf<String>()

        override suspend fun call(tool: String, arguments: JsonObject) = ToolResult.Answer(JsonObject(mapOf()))
    }

    @Test
    fun `each plugin entry says access for exactly the names in uses`() {
        val texts = plugin(listOf("messages", "contacts"), mapOf("messages" to listOf(READ), "contacts" to listOf(CONTACTS)))
        val phone = Phone(granted = setOf(READ), asked = setOf(CONTACTS))
        val entry = Plugins(listOf(texts)).entries(phone).single().jsonObject
        assertEquals(listOf("name", "tools", "confirms", "uses", "access"), entry.keys.toList())
        val access = entry["access"]!!.jsonObject
        assertEquals(listOf("messages", "contacts"), access.keys.toList())
        assertEquals("granted", access["messages"]!!.jsonPrimitive.content)
        assertEquals("refused", access["contacts"]!!.jsonPrimitive.content)
    }

    @Test
    fun `access is read again on every answer, and a refusal never changes uses`() {
        val texts = plugin(listOf("messages"), mapOf("messages" to listOf(READ)))
        val phone = Phone(granted = setOf(READ))
        val plugins = Plugins(listOf(texts))
        assertEquals("granted", plugins.entries(phone).single().jsonObject["access"]!!.jsonObject["messages"]!!.jsonPrimitive.content)
        phone.granted = setOf()
        phone.asked = setOf(READ)
        val entry = plugins.entries(phone).single().jsonObject
        assertEquals("refused", entry["access"]!!.jsonObject["messages"]!!.jsonPrimitive.content)
        assertEquals("""["messages"]""", entry["uses"].toString())
    }

    @Test
    fun `a plugin with no uses sends an empty access`() {
        val none = plugin(listOf(), mapOf())
        assertEquals("{}", Plugins(listOf(none)).entries(Phone()).single().jsonObject["access"].toString())
    }

    @Test
    fun `a plugin that names permissions for a use it does not know is refused at start`() {
        val odd = plugin(listOf("messages"), mapOf("telepathy" to listOf(READ)))
        assertThrows(IllegalArgumentException::class.java) { Plugins(listOf(odd)) }
    }
}
