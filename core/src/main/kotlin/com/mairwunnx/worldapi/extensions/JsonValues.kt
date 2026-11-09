package com.mairwunnx.worldapi.extensions

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.mairwunnx.worldapi.expansion.v2.Place
import java.time.temporal.TemporalAccessor
import java.util.UUID

/**
 * What expansions hand over, as JSON: records by their components, maps, lists and arrays, strings, numbers, and
 * booleans, as they'd write JSON themselves. UUIDs, enums, and times become strings; anything else
 * becomes its `toString()`, so a plugin's odd value never breaks a response.
 */
object JsonValues {
    /** [places] false leaves out every [Place], for a client that mustn't see coordinates. */
    fun of(value: Any?, places: Boolean = true, depth: Int = 0): JsonElement {
        if (depth > MAX_DEPTH) return JsonPrimitive("…")
        val child = { item: Any? -> of(item, places, depth + 1) }
        return when (value) {
            null -> JsonNull
            is JsonElement -> value
            is Record -> JsonObject(
                value.javaClass.recordComponents
                    .map { it.name to it.accessor.apply { isAccessible = true }.invoke(value) }
                    .filter { (_, item) -> places || item !is Place }
                    .associate { (name, item) -> name to child(item) },
            )
            is String -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is Number -> if (value is Double && !value.isFinite() || value is Float && !value.isFinite()) JsonNull else JsonPrimitive(value)
            is Char -> JsonPrimitive(value.toString())
            is UUID -> JsonPrimitive(value.toString())
            is Enum<*> -> JsonPrimitive(value.name.lowercase())
            is TemporalAccessor -> JsonPrimitive(value.toString())
            is Map<*, *> -> JsonObject(value.entries.filter { places || it.value !is Place }.associate { (key, item) -> key.toString() to child(item) })
            is Iterable<*> -> JsonArray(value.filter { places || it !is Place }.map(child))
            is Array<*> -> JsonArray(value.filter { places || it !is Place }.map(child))
            is IntArray -> JsonArray(value.map { JsonPrimitive(it) })
            is LongArray -> JsonArray(value.map { JsonPrimitive(it) })
            is DoubleArray -> JsonArray(value.map(child))
            else -> JsonPrimitive(value.toString())
        }
    }

    /** Deep enough for any honest payload; a cycle stops here instead of overflowing the stack. */
    private const val MAX_DEPTH = 32
}
