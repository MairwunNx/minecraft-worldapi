package com.mairwunnx.worldapi

import com.mairwunnx.worldapi.api.ApiJson
import com.mairwunnx.worldapi.api.HttpServer
import com.mairwunnx.worldapi.routes.Routes
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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
        val differences = differences("paths", paper["paths"], mods["paths"]) + differences("components", paper["components"], mods["components"])
        assertEquals(emptyList(), differences.take(20), "${differences.size} differences")
    }

    /** Where two documents differ, as JSON paths with both values: `paths./x.get.responses.200: Paper … mods …`. */
    private fun differences(at: String, paper: JsonElement?, mods: JsonElement?): List<String> = when {
        paper == mods -> emptyList()
        paper is JsonObject && mods is JsonObject -> (paper.keys + mods.keys).flatMap { differences("$at.$it", paper[it], mods[it]) }
        paper is JsonArray && mods is JsonArray && paper.size == mods.size -> paper.indices.flatMap { differences("$at[$it]", paper[it], mods[it]) }
        else -> listOf("$at: Paper ${paper.toString().take(200)}, mods ${mods.toString().take(200)}")
    }
}
