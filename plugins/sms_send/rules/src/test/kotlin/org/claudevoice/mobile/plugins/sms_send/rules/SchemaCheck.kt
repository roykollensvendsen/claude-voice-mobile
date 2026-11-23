package org.claudevoice.mobile.plugins.sms_send.rules

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * The part of JSON Schema that `docs/plugins/sms/` uses, checked the way
 * `tests/test_sms_since_shape.py` checks it; a copy of the sms rules' own, since one plugin's tests cannot reach another's: the project carries no JSON Schema library. A test
 * shows it refuses wrong answers, so it cannot pass everything.
 */
object SchemaCheck {
    /** The repository's `docs/plugins/sms` folder, found from wherever the test runs. */
    private val folder: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "docs/plugins/sms").isDirectory) dir = dir.parentFile
        File(checkNotNull(dir) { "no docs/plugins/sms above the test's folder" }, "docs/plugins/sms")
    }

    fun schema(name: String): JsonObject = Json.parseToJsonElement(File(folder, name).readText()).jsonObject

    /** What is wrong with [value] against [schema]; empty when it fits. */
    fun errors(value: JsonElement, schema: JsonObject): List<String> = errors(value, schema, schema, "$")

    private fun errors(value: JsonElement, node: JsonObject, root: JsonObject, at: String): List<String> {
        val ref = node["\$ref"]?.jsonPrimitive?.content
        if (ref != null) return errors(value, root["\$defs"]!!.jsonObject[ref.removePrefix("#/\$defs/")]!!.jsonObject, root, at)
        val wrong = mutableListOf<String>()
        node["type"]?.let { if (!typed(value, it)) wrong += "$at: $value is not $it" }
        node["const"]?.let { if (value != it) wrong += "$at: $value is not $it" }
        node["enum"]?.let { if (value !in it.jsonArray) wrong += "$at: $value is not one of $it" }
        when (value) {
            is JsonObject -> wrong += objectErrors(value, node, root, at)
            is JsonArray -> {
                node["maxItems"]?.jsonPrimitive?.intOrNull?.let { if (value.size > it) wrong += "$at: more than $it items" }
                val items = node["items"]?.jsonObject
                if (items != null) value.forEachIndexed { i, item -> wrong += errors(item, items, root, "$at[$i]") }
            }
            is JsonPrimitive -> wrong += scalarErrors(value, node, at)
            JsonNull -> Unit
        }
        return wrong
    }

    private fun objectErrors(value: JsonObject, node: JsonObject, root: JsonObject, at: String): List<String> {
        val wrong = mutableListOf<String>()
        val properties = node["properties"]?.jsonObject ?: JsonObject(emptyMap())
        node["required"]?.jsonArray?.forEach { key ->
            if (key.jsonPrimitive.content !in value) wrong += "$at: missing ${key.jsonPrimitive.content}"
        }
        for ((key, item) in value) {
            val property = properties[key]?.jsonObject
            if (property != null) {
                wrong += errors(item, property, root, "$at.$key")
            } else if (node["additionalProperties"]?.jsonPrimitive?.booleanOrNull == false) {
                wrong += "$at: $key is not allowed"
            }
        }
        return wrong
    }

    private fun scalarErrors(value: JsonPrimitive, node: JsonObject, at: String): List<String> {
        val wrong = mutableListOf<String>()
        node["minimum"]?.jsonPrimitive?.longOrNull?.let { min ->
            val number = value.longOrNull
            if (number != null && number < min) wrong += "$at: $number is below $min"
        }
        if (!value.isString) return wrong
        val text = value.content
        val length = text.codePointCount(0, text.length)
        node["minLength"]?.jsonPrimitive?.intOrNull?.let { if (length < it) wrong += "$at: shorter than $it" }
        node["maxLength"]?.jsonPrimitive?.intOrNull?.let { if (length > it) wrong += "$at: longer than $it" }
        node["pattern"]?.jsonPrimitive?.content?.let {
            if (!Regex(it).containsMatchIn(text)) wrong += "$at: '$text' does not match $it"
        }
        if (node["format"]?.jsonPrimitive?.contentOrNull == "date-time") {
            try {
                OffsetDateTime.parse(text)
            } catch (_: DateTimeParseException) {
                wrong += "$at: '$text' is no time with an offset"
            }
        }
        return wrong
    }

    private fun typed(value: JsonElement, type: JsonElement): Boolean {
        val allowed = if (type is JsonArray) type.map { it.jsonPrimitive.content } else listOf(type.jsonPrimitive.content)
        return allowed.any { name ->
            when (name) {
                "object" -> value is JsonObject
                "array" -> value is JsonArray
                "null" -> value is JsonNull
                "string" -> value is JsonPrimitive && value.isString
                "boolean" -> value is JsonPrimitive && !value.isString && value.booleanOrNull != null
                "integer" -> value is JsonPrimitive && !value.isString && value.longOrNull != null
                else -> error("the checker does not know type $name")
            }
        }
    }
}
