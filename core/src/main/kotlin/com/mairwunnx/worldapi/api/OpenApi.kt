package com.mairwunnx.worldapi.api

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.elementDescriptors
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Describes a path parameter the same way wherever it appears. */
private val pathParamDocs = mapOf(
    "player" to "Player UUID or name",
    "world" to "World name (`world_nether`) or key (`minecraft:the_nether`)",
    "x" to "Block X", "y" to "Block Y", "z" to "Block Z",
    "uuid" to "Entity UUID",
    "slot" to "Slot number; see the inventory layout in the description",
    "statistic" to "A preset (`playtime`, `diamonds`, `advancements`, `mobs`, `travel`, `deaths`, `blocks`, `fish`, `damage`) or any statistic as `category:key`, such as `custom:jump` or `killed:*`",
    "key" to "Namespaced key, such as `minecraft:story/mine_diamond`",
    "effect" to "Effect key, such as `minecraft:speed`",
    "chunkX" to "Chunk X", "chunkZ" to "Chunk Z",
    "id" to "Script id",
)

/**
 * Builds an OpenAPI 3.1 document from the endpoint list and the kotlinx.serialization
 * descriptors of the request and response classes.
 */
@OptIn(ExperimentalSerializationApi::class)
class OpenApi(private val title: String, private val version: String) {
    private val schemas = sortedMapOf<String, JsonElement>()
    private val names = mutableMapOf<String, SerialDescriptor>()

    fun document(endpoints: List<Endpoint>, serverUrl: String, extraPaths: Map<String, JsonObject> = emptyMap()): JsonObject {
        val paths = sortedMapOf<String, MutableMap<String, JsonElement>>()
        for (endpoint in endpoints) {
            paths.getOrPut(endpoint.documentedPath) { linkedMapOf() }[endpoint.method.value.lowercase()] = operation(endpoint)
        }
        extraPaths.forEach { (path, item) -> paths.getOrPut(path) { linkedMapOf() }.putAll(item) }
        schema(ErrorBody.serializer().descriptor)
        return buildJsonObject {
            put("openapi", "3.1.0")
            putJsonObject("info") {
                put("title", title)
                put("version", version)
                put("description", "REST access to a Paper server. Send a token as `Authorization: Bearer <token>`. Each operation lists the permission it needs in `x-scope` (or any of `x-scopes-any`), and in `x-also-needs` one that its full answer needs on top.")
            }
            putJsonArray("servers") { add(buildJsonObject { put("url", serverUrl) }) }
            putJsonArray("tags") {
                endpoints.map { it.tag }.distinct().forEach { tag -> add(buildJsonObject { put("name", tag) }) }
            }
            put("paths", JsonObject(paths.mapValues { JsonObject(it.value) }))
            putJsonObject("components") {
                putJsonObject("securitySchemes") {
                    putJsonObject("bearer") {
                        put("type", "http")
                        put("scheme", "bearer")
                    }
                }
                put("schemas", JsonObject(schemas))
            }
        }
    }

    private fun operation(endpoint: Endpoint) = buildJsonObject {
        put("operationId", operationId(endpoint))
        put("summary", endpoint.summary)
        val any = listOf(endpoint.scope) + endpoint.orScopes
        val needs = "Permission: " + any.joinToString(" or ") { "`${it.id}`" } + "." +
            (endpoint.also?.let { " Coordinates, items, and the like also need `${it.id}`." } ?: "")
        put("description", listOfNotNull(endpoint.description, needs).joinToString("\n\n"))
        putJsonArray("tags") { add(JsonPrimitive(endpoint.tag)) }
        put("x-scope", endpoint.scope.id)
        if (endpoint.orScopes.isNotEmpty()) putJsonArray("x-scopes-any") { any.forEach { add(JsonPrimitive(it.id)) } }
        endpoint.also?.let { put("x-also-needs", it.id) }
        putJsonArray("security") { add(buildJsonObject { putJsonArray("bearer") { add(JsonPrimitive(endpoint.scope.id)) } }) }
        val parameters = endpoint.pathParams.map { name ->
            buildJsonObject {
                put("name", name)
                put("in", "path")
                put("required", true)
                put("description", pathParamDocs[name] ?: name)
                putJsonObject("schema") { put("type", if (name in setOf("x", "y", "z", "slot", "chunkX", "chunkZ")) "integer" else "string") }
            }
        } + endpoint.query.map { param ->
            buildJsonObject {
                put("name", param.name)
                put("in", "query")
                put("required", param.required)
                put("description", param.description)
                putJsonObject("schema") {
                    put("type", param.type.schema)
                    param.values?.let { values -> put("enum", JsonArray(values.map(::JsonPrimitive))) }
                }
            }
        }
        if (parameters.isNotEmpty()) put("parameters", JsonArray(parameters))
        endpoint.body?.let { body ->
            putJsonObject("requestBody") {
                put("required", body.descriptor.elementsCount > 0 && (0 until body.descriptor.elementsCount).any { !body.descriptor.isElementOptional(it) })
                putJsonObject("content") { putJsonObject("application/json") { put("schema", ref(body)) } }
            }
        }
        putJsonObject("responses") {
            val response = endpoint.response
            if (response == null) {
                putJsonObject("204") { put("description", "Done") }
            } else {
                putJsonObject(endpoint.status.value.toString()) {
                    put("description", HttpStatusCode.fromValue(endpoint.status.value).description)
                    putJsonObject("content") { putJsonObject("application/json") { put("schema", ref(response)) } }
                }
            }
            val errors = buildList {
                if (endpoint.body != null || endpoint.query.isNotEmpty() || endpoint.pathParams.isNotEmpty()) add(400 to "Invalid request")
                add(401 to "Missing or unknown token")
                add(403 to "The token lacks the permission")
                if (endpoint.pathParams.isNotEmpty()) add(404 to "Not found")
                add(429 to "Rate limit hit; see Retry-After")
                add(503 to "The server thread didn't answer in time")
            }
            errors.forEach { (status, description) ->
                putJsonObject(status.toString()) {
                    put("description", description)
                    putJsonObject("content") { putJsonObject("application/json") { putJsonObject("schema") { put("\$ref", "#/components/schemas/ErrorBody") } } }
                }
            }
        }
    }

    private fun ref(serializer: KSerializer<*>): JsonElement = schema(serializer.descriptor)

    /** Inline schema for primitives, collections, and enums; a component reference for classes. */
    private fun schema(descriptor: SerialDescriptor): JsonElement {
        val base = when {
            descriptor.serialName.startsWith("kotlinx.serialization.json.JsonObject") -> buildJsonObject { put("type", "object") }
            descriptor.serialName.startsWith("kotlinx.serialization.json.JsonElement") -> JsonObject(emptyMap())
            descriptor.serialName.startsWith("kotlinx.serialization.json.JsonPrimitive") -> buildJsonObject {
                putJsonArray("type") { listOf("string", "number", "boolean").forEach { add(JsonPrimitive(it)) } }
            }
            else -> when (val kind = descriptor.kind) {
                is PrimitiveKind -> buildJsonObject { primitive(kind) }
                SerialKind.ENUM -> buildJsonObject {
                    put("type", "string")
                    put("enum", JsonArray((0 until descriptor.elementsCount).map { JsonPrimitive(descriptor.getElementName(it)) }))
                }
                StructureKind.LIST -> buildJsonObject {
                    put("type", "array")
                    put("items", schema(descriptor.getElementDescriptor(0)))
                }
                StructureKind.MAP -> buildJsonObject {
                    put("type", "object")
                    put("additionalProperties", schema(descriptor.getElementDescriptor(1)))
                }
                StructureKind.CLASS, StructureKind.OBJECT -> component(descriptor)
                is PolymorphicKind, SerialKind.CONTEXTUAL -> JsonObject(emptyMap())
            }
        }
        return if (descriptor.isNullable) nullable(base) else base
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.primitive(kind: PrimitiveKind) {
        when (kind) {
            PrimitiveKind.BOOLEAN -> put("type", "boolean")
            PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT -> { put("type", "integer"); put("format", "int32") }
            PrimitiveKind.LONG -> { put("type", "integer"); put("format", "int64") }
            PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> put("type", "number")
            PrimitiveKind.CHAR, PrimitiveKind.STRING -> put("type", "string")
        }
    }

    private fun nullable(schema: JsonElement): JsonElement {
        val obj = schema as? JsonObject ?: return schema
        if (obj.containsKey("\$ref")) return buildJsonObject { putJsonArray("oneOf") { add(obj); add(buildJsonObject { put("type", "null") }) } }
        val type = obj["type"] as? JsonPrimitive ?: return obj
        return JsonObject(obj + ("type" to buildJsonArray { add(type); add(JsonPrimitive("null")) }))
    }

    private fun component(descriptor: SerialDescriptor): JsonElement {
        val name = componentName(descriptor)
        val existing = names[name]
        check(existing == null || existing.serialName.removeSuffix("?") == descriptor.serialName.removeSuffix("?") && existing.elementsCount == descriptor.elementsCount && sameElements(existing, descriptor)) {
            "Two schemas want the name $name: ${existing?.serialName} and ${descriptor.serialName}"
        }
        if (existing == null) {
            names[name] = descriptor
            schemas[name] = JsonObject(emptyMap()) // placeholder, so recursive types terminate
            schemas[name] = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    for (i in 0 until descriptor.elementsCount) {
                        put(descriptor.getElementName(i), schema(descriptor.getElementDescriptor(i)))
                    }
                }
                val required = (0 until descriptor.elementsCount).filter { !descriptor.isElementOptional(it) && !descriptor.getElementDescriptor(it).isNullable }
                if (required.isNotEmpty()) put("required", JsonArray(required.map { JsonPrimitive(descriptor.getElementName(it)) }))
                put("additionalProperties", false)
            }
        }
        return buildJsonObject { put("\$ref", "#/components/schemas/$name") }
    }

    private fun sameElements(a: SerialDescriptor, b: SerialDescriptor) =
        a.elementDescriptors.map { it.serialName } == b.elementDescriptors.map { it.serialName }

    /** `Page<PlayerSummary>` becomes `PlayerSummaryPage`; everything else keeps its class name. */
    private fun componentName(descriptor: SerialDescriptor): String {
        val simple = descriptor.serialName.substringAfterLast('.').removeSuffix("?")
        if (descriptor.serialName == Page::class.qualifiedName) {
            val item = descriptor.getElementDescriptor(0).getElementDescriptor(0)
            return componentName(item) + "Page"
        }
        return simple
    }

    companion object {
        fun operationId(endpoint: Endpoint): String {
            val words = endpoint.documentedPath.split('/').filter { it.isNotEmpty() }.map {
                it.removePrefix("{").removeSuffix("}").split('-', '_', '.').joinToString("") { part -> part.replaceFirstChar(Char::uppercase) }
            }
            return endpoint.method.value.lowercase() + words.joinToString("")
        }
    }
}
