package com.mairwunnx.worldapi

import com.mairwunnx.worldapi.api.ApiJson
import com.mairwunnx.worldapi.api.HttpServer
import com.mairwunnx.worldapi.api.OpenApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class OpenApiTest {
    private val document = HttpServer.document(Routes.all, "1.0.0", "/api/v1")
    private val pretty = Json(ApiJson) { prettyPrint = true }

    @Test
    fun `every endpoint is in the document`() {
        val paths = document["paths"]!!.jsonObject
        for (endpoint in Routes.all) {
            val item = paths[endpoint.documentedPath]?.jsonObject ?: fail("${endpoint.documentedPath} is missing")
            assertTrue(endpoint.method.value.lowercase() in item, "${endpoint.method.value} ${endpoint.path} is missing")
        }
        assertTrue("/ws" in paths)
    }

    @Test
    fun `operation ids are unique`() {
        val ids = Routes.all.map(OpenApi::operationId)
        assertEquals(ids.size, ids.toSet().size, ids.groupBy { it }.filter { it.value.size > 1 }.keys.toString())
    }

    @Test
    fun `every reference resolves`() {
        val schemas = document["components"]!!.jsonObject["schemas"]!!.jsonObject
        fun walk(element: JsonElement) {
            when (element) {
                is JsonObject -> element.forEach { (key, value) ->
                    if (key == "\$ref") {
                        val name = value.jsonPrimitive.content.removePrefix("#/components/schemas/")
                        assertTrue(name in schemas, "Unresolved $name")
                    } else walk(value)
                }
                is JsonArray -> element.forEach(::walk)
                else -> {}
            }
        }
        walk(document)
    }

    /** The MCP server builds its client from docs/openapi.json; this keeps that file current. */
    @Test
    fun `the committed document is current`() {
        val file = Path.of(System.getProperty("worldapi.openapi") ?: "docs/openapi.json")
        val text = pretty.encodeToString(JsonObject.serializer(), document) + "\n"
        if (System.getenv("WORLDAPI_UPDATE_OPENAPI") == "1") {
            file.parent.toFile().mkdirs()
            file.writeText(text)
        }
        assertTrue(file.exists(), "Run WORLDAPI_UPDATE_OPENAPI=1 ./gradlew test to write docs/openapi.json")
        assertEquals(text, file.readText(), "docs/openapi.json is stale; run WORLDAPI_UPDATE_OPENAPI=1 ./gradlew test")
    }
}
