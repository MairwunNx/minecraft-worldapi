package com.mairwunnx.worldapi

import com.mairwunnx.worldapi.api.Principal
import com.mairwunnx.worldapi.api.Scope
import com.mairwunnx.worldapi.config.Extensions as ExtensionSettings
import com.mairwunnx.worldapi.config.WorldApiConfig
import com.mairwunnx.worldapi.events.Topic
import com.mairwunnx.worldapi.events.Visibility
import com.mairwunnx.worldapi.extensions.Extensions
import com.mairwunnx.worldapi.extensions.JsonValues
import com.mairwunnx.worldapi.extensions.owner
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID
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
    private val extensions = Extensions(fake.bus, com.mairwunnx.worldapi.core.MainThread.Immediate, Logger.getAnonymousLogger())
    private val plugin = fakePlugin()

    @AfterTest
    fun close() {
        extensions.unregister("mobosses")
        fake.close()
    }

    private fun register(owner: org.bukkit.plugin.Plugin = plugin) = extensions.register(
        Extensions.Namespace(
            "mobosses", owner.owner, "Bosses",
            listOf(
                Extensions.Resource("hunters", "", public = true, mainThread = false) { _, _, _ -> listOf(mapOf("name" to "Steve")) },
                Extensions.Resource("players/{player}", "", public = true, mainThread = false) { path, _, _ -> mapOf("name" to path["player"]) },
                Extensions.Resource("bosses", "", public = false, mainThread = true) { _, _, _ -> emptyList<Any>() },
            ),
            listOf(
                Topic.extension("mobosses", "boss.killed", recorded = true, public = true, description = ""),
                Topic.extension("mobosses", "boss.spawned", recorded = false, public = false, description = ""),
            ),
        ),
    )

    @Test
    fun `a namespace holds its resources and topics`() {
        register()
        val namespace = assertNotNull(extensions.namespace("mobosses"))
        assertEquals(listOf("hunters", "players/{player}", "bosses"), namespace.resources.map { it.path })
        val killed = assertNotNull(Topic.of("ext.mobosses.boss.killed"))
        assertTrue(killed.recorded)
        assertTrue(killed in Topic.entries)
        assertEquals(mapOf("player" to "Steve"), namespace.resources[1].match(listOf("players", "Steve")))
        assertNull(namespace.resources[1].match(listOf("players")))
    }

    @Test
    fun `an unregistered namespace takes its topics along, and nobody else takes it`() {
        register()
        assertFailsWith<IllegalArgumentException> { register(fakePlugin()) }
        extensions.unregister("mobosses")
        assertNull(extensions.namespace("mobosses"))
        assertNull(Topic.of("ext.mobosses.boss.killed"))
    }

    @Test
    fun `anonymous clients see public topics only, unless the server keeps them private`() {
        register()
        val anonymous = Principal.anonymous("127.0.0.1")
        val token = Principal("site", Scope.legacy.getValue("read"), "token")
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
