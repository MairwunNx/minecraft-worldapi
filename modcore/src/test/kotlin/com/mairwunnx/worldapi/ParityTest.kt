package com.mairwunnx.worldapi

import com.mairwunnx.worldapi.api.ApiJson
import com.mairwunnx.worldapi.api.HttpServer
import com.mairwunnx.worldapi.routes.Routes
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The mods serve the same API as Paper: their endpoints make the same OpenAPI document, word for word, as the one
 * Paper's build writes to docs/openapi.json. Clients read one document for every platform.
 */
class ParityTest {
    private val document = Path.of(System.getProperty("worldapi.openapi"))

    @Test
    fun `the mods' endpoints make Paper's document`() {
        val paper = ApiJson.parseToJsonElement(Files.readString(document)).jsonObject
        val mods = HttpServer.document(Routes.all, "dev", "/api/v1")
        val paperPaths = (paper["paths"] as JsonObject).keys
        val modPaths = (mods["paths"] as JsonObject).keys
        assertEquals(paperPaths.sorted(), modPaths.sorted(), "Paths only Paper has: ${paperPaths - modPaths}; only the mods: ${modPaths - paperPaths}")
        for (path in paperPaths) {
            assertEquals(paper["paths"]!!.jsonObject[path], mods["paths"]!!.jsonObject[path], "$path differs")
        }
        assertEquals(paper["components"], mods["components"])
    }
}
