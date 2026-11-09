package com.mairwunnx.worldapi.extensions

import com.mairwunnx.worldapi.expansion.v2.Place
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.lang.reflect.GenericArrayType
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.WildcardType
import java.math.BigDecimal
import java.math.BigInteger
import java.time.temporal.TemporalAccessor
import java.util.UUID

/**
 * The shapes of an API 2 expansion's records, as JSON Schema, for `GET /extensions`: clients and the MCP server learn
 * what an event or a resource holds without reading the plugin's code. They follow what [JsonValues] writes.
 */
object Schemas {
    fun of(type: Type, depth: Int = 0): JsonObject {
        if (depth > MAX_DEPTH) return JsonObject(emptyMap())
        val child = { item: Type -> of(item, depth + 1) }
        return when (type) {
            is WildcardType -> child(type.upperBounds.first())
            is GenericArrayType -> array(child(type.genericComponentType))
            is ParameterizedType -> {
                val raw = type.rawType as Class<*>
                when {
                    Map::class.java.isAssignableFrom(raw) -> JsonObject(mapOf("type" to JsonPrimitive("object"), "additionalProperties" to child(type.actualTypeArguments[1])))
                    Iterable::class.java.isAssignableFrom(raw) -> array(child(type.actualTypeArguments[0]))
                    else -> child(raw)
                }
            }
            is Class<*> -> when {
                type == String::class.java || type == Char::class.javaPrimitiveType || type == Char::class.javaObjectType -> plain("string")
                type == Boolean::class.javaPrimitiveType || type == Boolean::class.javaObjectType -> plain("boolean")
                type in WHOLE || type == BigInteger::class.java -> plain("integer")
                type == Double::class.javaPrimitiveType || type == Float::class.javaPrimitiveType || Number::class.java.isAssignableFrom(type) || type == BigDecimal::class.java -> plain("number")
                type == UUID::class.java -> plain("string", "format" to JsonPrimitive("uuid"))
                TemporalAccessor::class.java.isAssignableFrom(type) -> plain("string", "format" to JsonPrimitive("date-time"))
                type.isEnum -> plain("string", "enum" to JsonArray(type.enumConstants.map { JsonPrimitive((it as Enum<*>).name.lowercase()) }))
                type.isRecord -> JsonObject(mapOf("type" to JsonPrimitive("object"), "properties" to JsonObject(type.recordComponents.associate { it.name to child(it.genericType) })))
                type.isArray -> array(child(type.componentType))
                Map::class.java.isAssignableFrom(type) -> plain("object")
                Iterable::class.java.isAssignableFrom(type) -> plain("array")
                else -> JsonObject(emptyMap())
            }
            else -> JsonObject(emptyMap())
        }
    }

    /** A list of [type]'s values. */
    fun listOf(type: Type): JsonObject = array(of(type))

    /** Names of a record's components, at any depth, that hold a [Place]: what anonymous clients don't get. */
    fun placeKeys(type: Class<*>): Set<String> = buildSet { collectPlaces(type, this, 0) }

    private fun collectPlaces(type: Class<*>, into: MutableSet<String>, depth: Int) {
        if (!type.isRecord || depth > MAX_DEPTH) return
        type.recordComponents.forEach { component ->
            val holds = classes(component.genericType)
            if (Place::class.java in holds) into += component.name
            holds.filter { it != Place::class.java && it != type }.forEach { collectPlaces(it, into, depth + 1) }
        }
    }

    /** Every class a type names: the class itself, a list's items, a map's values. */
    private fun classes(type: Type): List<Class<*>> = when (type) {
        is Class<*> -> if (type.isArray) classes(type.componentType) else listOf<Class<*>>(type)
        is ParameterizedType -> type.actualTypeArguments.flatMap(::classes)
        is WildcardType -> type.upperBounds.flatMap(::classes)
        is GenericArrayType -> classes(type.genericComponentType)
        else -> emptyList()
    }

    private fun plain(type: String, vararg extra: Pair<String, JsonPrimitive>) = JsonObject(mapOf("type" to JsonPrimitive(type)) + extra)

    private fun plain(type: String, extra: Pair<String, JsonArray>) = JsonObject(mapOf("type" to JsonPrimitive(type), extra))

    private fun array(items: JsonObject) = JsonObject(mapOf("type" to JsonPrimitive("array"), "items" to items))

    private val WHOLE = setOf(
        Int::class.javaPrimitiveType, Int::class.javaObjectType, Long::class.javaPrimitiveType, Long::class.javaObjectType,
        Short::class.javaPrimitiveType, Short::class.javaObjectType, Byte::class.javaPrimitiveType, Byte::class.javaObjectType,
    )

    private const val MAX_DEPTH = 8
}
