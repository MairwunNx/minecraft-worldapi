package com.mairwunnx.worldapi

import com.mairwunnx.worldapi.api.Principal
import com.mairwunnx.worldapi.api.Scope
import com.mairwunnx.worldapi.config.Extensions as ExtensionSettings
import com.mairwunnx.worldapi.config.WorldApiConfig
import com.mairwunnx.worldapi.events.Topic
import com.mairwunnx.worldapi.events.Visibility
import com.mairwunnx.worldapi.extensions.Bridge
import com.mairwunnx.worldapi.extensions.Extensions
import com.mairwunnx.worldapi.extensions.JsonValues
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID
import java.util.function.Function
import java.util.logging.Logger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExtensionTest {
    private val fake = TestApi(WorldApiConfig())
    private val extensions = Extensions(fake.bus, Logger.getAnonymousLogger())
    private val bridge = Bridge(extensions, "test")
    private val plugin = fakePlugin()

    @AfterTest
    fun close() {
        extensions.unregister("mobosses")
        fake.close()
    }

    private fun register(owner: org.bukkit.plugin.Plugin = plugin) = bridge.apply(
        mapOf(
            "v" to 1, "op" to "register", "plugin" to owner, "namespace" to "mobosses", "description" to "Bosses",
            "resources" to listOf(
                mapOf("path" to "hunters", "public" to true, "get" to Function<Map<String, String>, Any?> { listOf(mapOf("name" to "Steve")) }),
                mapOf("path" to "players/{player}", "public" to true, "get" to Function<Map<String, String>, Any?> { mapOf("name" to it["player"]) }),
                mapOf("path" to "bosses", "get" to Function<Map<String, String>, Any?> { emptyList<Any>() }),
            ),
            "events" to listOf(
                mapOf("name" to "boss.killed", "public" to true, "recorded" to true),
                mapOf("name" to "boss.spawned"),
            ),
        ),
    )

    @Test
    fun `a plugin registers resources and topics with plain java values`() {
        assertEquals(true, (register() as Map<*, *>)["ok"])
        val namespace = assertNotNull(extensions.namespace("mobosses"))
        assertEquals(listOf("hunters", "players/{player}", "bosses"), namespace.resources.map { it.path })
        val killed = assertNotNull(Topic.of("ext.mobosses.boss.killed"))
        assertTrue(killed.recorded)
        assertTrue(killed in Topic.entries)
        assertEquals(mapOf("player" to "Steve"), namespace.resources[1].match(listOf("players", "Steve")))
        assertNull(namespace.resources[1].match(listOf("players")))
    }

    @Test
    fun `a stopped plugin takes its topics along, and nobody else takes its namespace`() {
        register()
        assertFailsWith<IllegalArgumentException> { register(fakePlugin()) }
        extensions.unregisterAll(plugin)
        assertNull(extensions.namespace("mobosses"))
        assertNull(Topic.of("ext.mobosses.boss.killed"))
    }

    @Test
    fun `requests from a newer contract or with bad names are refused`() {
        assertFailsWith<IllegalArgumentException> { bridge.apply(mapOf("v" to Bridge.CONTRACT + 1, "op" to "unregister", "namespace" to "mobosses")) }
        assertFailsWith<IllegalArgumentException> { bridge.apply(mapOf("v" to 1, "op" to "register", "plugin" to plugin, "namespace" to "Mo Bosses")) }
        assertFailsWith<IllegalArgumentException> { bridge.apply(mapOf("op" to "register")) }
    }

    @Test
    fun `anonymous clients see public topics only, unless the server keeps them private`() {
        register()
        val anonymous = Principal.anonymous("127.0.0.1")
        val token = Principal("site", setOf(Scope.READ), "token")
        val killed = Topic.of("ext.mobosses.boss.killed")!!
        val spawned = Topic.of("ext.mobosses.boss.spawned")!!
        assertTrue(Visibility(anonymous, WorldApiConfig()).allows(killed))
        assertFalse(Visibility(anonymous, WorldApiConfig()).allows(spawned))
        assertTrue(Visibility(token, WorldApiConfig()).allows(spawned))
        assertFalse(Visibility(anonymous, WorldApiConfig(extensions = ExtensionSettings(private = listOf("mobosses")))).allows(killed))
        assertFalse(Visibility(token, WorldApiConfig(extensions = ExtensionSettings(enabled = false))).allows(killed))
    }

    @Test
    fun `plain java values become json`() {
        val id = UUID(0, 1)
        val json = JsonValues.of(mapOf("id" to id, "level" to 30, "share" to 0.5, "nan" to Double.NaN, "tags" to arrayOf("a", "b"), "none" to null))
        assertEquals(
            JsonObject(
                mapOf(
                    "id" to JsonPrimitive(id.toString()), "level" to JsonPrimitive(30), "share" to JsonPrimitive(0.5), "nan" to JsonNull,
                    "tags" to JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b"))), "none" to JsonNull,
                ),
            ),
            json,
        )
    }
}
