package com.mairwunnx.worldapi

import com.mairwunnx.worldapi.api.HttpServer
import com.mairwunnx.worldapi.config.Anonymous
import com.mairwunnx.worldapi.config.Auth
import com.mairwunnx.worldapi.config.Token
import com.mairwunnx.worldapi.config.WorldApiConfig
import com.mairwunnx.worldapi.events.Topic
import com.mairwunnx.worldapi.expansion.EventType
import com.mairwunnx.worldapi.expansion.ExpansionApi
import com.mairwunnx.worldapi.expansion.Resource
import com.mairwunnx.worldapi.expansion.WorldApiExpansion
import com.mairwunnx.worldapi.expansion.WorldApiExpansions
import com.mairwunnx.worldapi.extensions.Expansions
import com.mairwunnx.worldapi.extensions.owner
import com.mairwunnx.worldapi.routes.extensionRoutes
import com.mairwunnx.worldapi.ws.WebSocketHub
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.bukkit.plugin.Plugin
import java.util.NoSuchElementException
import java.util.concurrent.CompletableFuture
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExpansionTest {
    private val read = "read-token-0123456789"
    private val config = WorldApiConfig(
        auth = Auth(tokens = listOf(Token("site", read, listOf("viewer"))), anonymous = Anonymous(endpoints = listOf("/extensions/**"))),
    )
    private val fake = TestApi(config)
    private val warnings = mutableListOf<String>()
    private val logger = Logger.getAnonymousLogger().apply {
        useParentHandlers = false
        addHandler(object : Handler() {
            override fun publish(record: LogRecord) { warnings += record.message }
            override fun flush() {}
            override fun close() {}
        })
    }
    private val bosses = fakePlugin()
    private val other = fakePlugin()
    private val owners = mutableMapOf<WorldApiExpansion, Plugin>()
    private val host = Expansions(fake.api.extensions, logger) { (owners[it] ?: bosses).owner }

    @AfterTest
    fun close() {
        WorldApiExpansions.bind(null)
        WorldApiExpansions.registered().forEach { WorldApiExpansions.unregister(it.id()) }
        listOf("mobosses", "newer", "taken").forEach(fake.api.extensions::unregister)
        fake.close()
    }

    private class Bosses(
        private val id: String = "mobosses",
        private val api: String = ExpansionApi.VERSION,
        private val events: List<EventType> = listOf(EventType.of("boss.killed", "A boss died").publicly().recorded(), EventType.of("boss.spawned", "")),
    ) : WorldApiExpansion {
        override fun id() = id
        override fun requiresApi() = api
        override fun description() = "Bosses"
        override fun events() = events
        override fun resources() = listOf(
            Resource.of("hunters", "The best hunters; ?limit=1..50", { request ->
                List(request.query("limit", 2, 1, 50)) { mapOf("place" to it + 1) }
            }).publicly(),
            Resource.of("players/{player}", "A card", { request ->
                if (request.path("player") == "nobody") throw NoSuchElementException("No player named nobody")
                CompletableFuture.supplyAsync { mapOf("name" to request.path("player"), "anonymous" to request.anonymous(), "private" to request.can("players:private")) }
            }).publicly(),
            Resource.of("bosses", "Where the bosses are", { request -> mapOf("coordinates" to request.seesCoordinates()) }).onServerThread(),
        )
    }

    @Test
    fun `an expansion becomes a namespace with resources and topics`() {
        WorldApiExpansions.register(Bosses())
        WorldApiExpansions.bind(host)
        val namespace = assertNotNull(fake.api.extensions.namespace("mobosses"))
        assertTrue(namespace.plugin.key === bosses)
        assertEquals("Bosses", namespace.description)
        assertEquals(listOf("hunters", "players/{player}", "bosses"), namespace.resources.map { it.path })
        assertEquals(listOf(true, true, false), namespace.resources.map { it.public })
        assertEquals(listOf(false, false, true), namespace.resources.map { it.mainThread })
        val killed = assertNotNull(Topic.of("ext.mobosses.boss.killed"))
        assertTrue(killed.recorded)
        assertTrue(killed.extension!!.public)
        assertTrue(Topic.of("ext.mobosses.boss.spawned")!!.extension!!.public.not())
    }

    @Test
    fun `broken expansions are refused with a line in the log`() {
        WorldApiExpansions.bind(host)
        WorldApiExpansions.register(Bosses(id = "newer", api = "1.99.0"))
        WorldApiExpansions.register(Bosses(id = "Not An Id"))
        WorldApiExpansions.register(Bosses(id = "taken", events = listOf(EventType.of("twice", ""), EventType.of("twice", ""))))
        assertNull(fake.api.extensions.namespace("newer"))
        assertNull(fake.api.extensions.namespace("taken"))
        assertEquals(3, warnings.size, warnings.toString())
        assertTrue("needs expansion API 1.99.0" in warnings[0])
        // Refused, an expansion publishes into nothing instead of failing its plugin.
        assertTrue(WorldApiExpansions.publish("newer", com.mairwunnx.worldapi.expansion.Event.of("boss.killed", mapOf<String, Any>())))
    }

    @Test
    fun `nobody takes another plugin's namespace`() {
        WorldApiExpansions.bind(host)
        WorldApiExpansions.register(Bosses(id = "taken").also { owners[it] = other })
        WorldApiExpansions.register(Bosses(id = "taken"))
        assertTrue(fake.api.extensions.namespace("taken")!!.plugin.key === other)
        assertTrue("belongs to" in warnings.single())
    }

    @Test
    fun `a stopped plugin's expansions leave WorldAPI and the API's list`() {
        WorldApiExpansions.bind(host)
        WorldApiExpansions.register(Bosses())
        host.stopped(other.owner)
        assertNotNull(fake.api.extensions.namespace("mobosses"))
        host.stopped(bosses.owner)
        assertNull(fake.api.extensions.namespace("mobosses"))
        assertNull(Topic.of("ext.mobosses.boss.killed"))
        assertTrue(WorldApiExpansions.registered().isEmpty())
    }

    @Test
    fun `resources answer over HTTP with the request's parameters and who asks`() = testApplication {
        WorldApiExpansions.bind(host)
        WorldApiExpansions.register(Bosses())
        val hub = WebSocketHub(fake.ref::get, { null }) { _, _ -> emptyList() }
        application { HttpServer(fake.api, extensionRoutes, hub, Logger.getAnonymousLogger()).configure(this) }

        assertEquals("""[{"place":1},{"place":2}]""", client.get("/api/v1/extensions/mobosses/hunters").bodyAsText())
        assertEquals(50, Regex("place").findAll(client.get("/api/v1/extensions/mobosses/hunters?limit=900").bodyAsText()).count())
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/extensions/mobosses/hunters?limit=five").status)
        assertEquals("""{"name":"Steve","anonymous":true,"private":false}""", client.get("/api/v1/extensions/mobosses/players/Steve").bodyAsText())
        // The viewer group holds players:private, so it sees coordinates too.
        assertEquals("""{"name":"Steve","anonymous":false,"private":true}""", client.get("/api/v1/extensions/mobosses/players/Steve") { bearerAuth(read) }.bodyAsText())
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/extensions/mobosses/players/nobody").status)
        // Private resources need a token, even on a path visitors may read.
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/extensions/mobosses/bosses").status)
        assertEquals("""{"coordinates":true}""", client.get("/api/v1/extensions/mobosses/bosses") { bearerAuth(read) }.bodyAsText())
    }
}
