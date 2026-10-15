package com.mairwunnx.worldapi.extensions

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.temporal.TemporalAccessor
import java.util.UUID

/**
 * What plugins hand over through the bridge, as JSON: maps, lists and arrays, strings, numbers, and
 * booleans, as they'd write JSON themselves. UUIDs, enums, and times become strings; anything else
 * becomes its `toString()`, so a plugin's odd value never breaks a response.
 */
object JsonValues {
    fun of(value: Any?, depth: Int = 0): JsonElement {
        if (depth > MAX_DEPTH) return JsonPrimitive("…")
        return when (value) {
            null -> JsonNull
            is JsonElement -> value
            is String -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is Number -> if (value is Double && !value.isFinite() || value is Float && !value.isFinite()) JsonNull else JsonPrimitive(value)
            is Char -> JsonPrimitive(value.toString())
            is UUID -> JsonPrimitive(value.toString())
            is Enum<*> -> JsonPrimitive(value.name.lowercase())
            is TemporalAccessor -> JsonPrimitive(value.toString())
            is Map<*, *> -> JsonObject(value.entries.associate { (key, item) -> key.toString() to of(item, depth + 1) })
            is Iterable<*> -> JsonArray(value.map { of(it, depth + 1) })
            is Array<*> -> JsonArray(value.map { of(it, depth + 1) })
            is IntArray -> JsonArray(value.map { JsonPrimitive(it) })
            is LongArray -> JsonArray(value.map { JsonPrimitive(it) })
            is DoubleArray -> JsonArray(value.map { of(it, depth + 1) })
            else -> JsonPrimitive(value.toString())
        }
    }

    /** Deep enough for any honest payload; a cycle stops here instead of overflowing the stack. */
    private const val MAX_DEPTH = 32
}
