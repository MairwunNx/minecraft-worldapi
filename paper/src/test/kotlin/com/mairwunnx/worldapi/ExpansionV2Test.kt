package com.mairwunnx.worldapi

import com.mairwunnx.worldapi.api.HttpServer
import com.mairwunnx.worldapi.config.Anonymous
import com.mairwunnx.worldapi.config.Auth
import com.mairwunnx.worldapi.config.Token
import com.mairwunnx.worldapi.config.WorldApiConfig
import com.mairwunnx.worldapi.events.ApiEvent
import com.mairwunnx.worldapi.events.EventBus
import com.mairwunnx.worldapi.events.Topic
import com.mairwunnx.worldapi.events.Vanished
import com.mairwunnx.worldapi.expansion.v2.Expansion
import com.mairwunnx.worldapi.expansion.v2.Permission
import com.mairwunnx.worldapi.expansion.v2.Place
import com.mairwunnx.worldapi.expansion.v2.PlayerRef
import com.mairwunnx.worldapi.expansion.v2.Registry
import com.mairwunnx.worldapi.extensions.ExpansionServices
import com.mairwunnx.worldapi.extensions.Expansions
import com.mairwunnx.worldapi.extensions.Extensions
import com.mairwunnx.worldapi.routes.extensionRoutes
import com.mairwunnx.worldapi.ws.WebSocketHub
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLClassLoader
import java.nio.file.Files
import java.util.UUID
import java.util.logging.Logger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.mairwunnx.worldapi.model.PlayerRef as ModelPlayerRef

private val steve = UUID.fromString("00000000-0000-0000-0000-00000000000a")
private val alex = UUID.fromString("00000000-0000-0000-0000-00000000000b")

@JvmRecord data class Hunter(val player: PlayerRef, val kills: Int)

@JvmRecord data class Lair(val name: String, val place: Place)

@JvmRecord data class BossKilled(val killer: PlayerRef, val helpers: List<PlayerRef>, val type: String, val level: Int, val place: Place)

enum class Sort { KILLS, LEVEL }

/** An expansion as a Kotlin plugin writes it: an object whose fields declare what it shares. */
object Hunting : Expansion("hunting", "Hunting", "Bosses and their hunters") {
    val limit = integer("limit", "How many hunters", 1, 50, 2)
    val sort = choice("sort", "How to sort them", Sort::class.java, Sort.KILLS)
    val player = player("player", "A player’s name or UUID")

    val hunters = resource("hunters", "The best hunters") { req -> List(req[limit]) { Hunter(PlayerRef(steve, "Steve"), it) } }.query(limit, sort).returnsListOf(Hunter::class.java).publicly()
    val card = resource("players/{player}", "A player’s card") { req -> mapOf("player" to req[player], "private" to req.can(Permission.PLAYERS_PRIVATE)) }.publicly()
    val few = resource("few", "The three best hunters, or up to ten") { req -> List(req[limit]) { Hunter(PlayerRef(steve, "Steve"), it) } }.query(limit).orElse(limit, 3).between(limit, 1, 10).publicly()
    val lair = resource("lair", "Where the boss sleeps") { Lair("cave", Place("world", 1.0, 64.0, 2.0)) }.returns(Lair::class.java).publicly()

    val killed = event("boss.killed", BossKilled::class.java, "Players killed a boss").publicly().recorded()
}

@JvmRecord data class Spawned(val type: String, val level: Int, val at: Place)

/** An expansion with actions: what an MCP client asks a plugin to do. */
object Bosses : Expansion("bosses", "Bosses", "Bosses on request") {
    val type = text("type", "A boss type, like zombie").required()
    val level = integer("level", "The boss level", 1, 100, 10)
    val at = place("at", "Where to spawn it").required()
    val player = player("player", "A player’s name or UUID")
    val reason = text("reason", "Why")

    val spawn = action("bosses/spawn", "Spawns a boss", ::spawn).body(type, level, at).permission(Permission.WORLD_WRITE).returns(Spawned::class.java)
    val afk = action("players/{player}/afk", "Puts a player into AFK", ::afk).body(reason).permission(Permission.PLAYERS_MODERATE)
    val back = remove("players/{player}/afk", "Takes a player out of AFK", ::back).permission(Permission.PLAYERS_MODERATE)

    private fun spawn(req: com.mairwunnx.worldapi.expansion.v2.Request) = Spawned(req[type], req[level], req[at])
    private fun afk(req: com.mairwunnx.worldapi.expansion.v2.Request) = mapOf("afk" to req[player].name, "reason" to req[reason])
    private fun back(req: com.mairwunnx.worldapi.expansion.v2.Request) = mapOf("back" to req[player].name)
}

/** An action that names no permission: WorldAPI refuses the whole expansion. */
object Careless : Expansion("careless") {
    val wipe = action("wipe", "Wipes everything", { "wiped" })
}

/** An expansion as a Java plugin writes it: a class with a constructor that takes nothing. */
class Plain : Expansion("plain")

class ExpansionV2Test {
    private val read = "read-token-0123456789"
    private val admin = "admin-token-0123456789"
    private val config = WorldApiConfig(
        auth = Auth(tokens = listOf(Token("site", read, listOf("viewer")), Token("admin", admin, listOf("admin"))), anonymous = Anonymous(endpoints = listOf("/extensions/**"))),
    )
    private val fake = TestApi(config)
    private val players = { id: String -> if (id == "Steve" || id == steve.toString()) ModelPlayerRef(steve.toString(), "Steve") else null }
    private val owner = Extensions.Owner("Hunting", this) { "1.0.0" }
    private val host = Expansions(fake.api.extensions, Logger.getAnonymousLogger(), players) { owner }

    @AfterTest
    fun close() {
        Registry.bind(null)
        Registry.registered().forEach { Registry.unregister(it.id()) }
        listOf("hunting", "plain", "bosses", "careless").forEach(fake.api.extensions::unregister)
        fake.close()
    }

    @Test
    fun `an object's fields become a namespace with parameters, schemas, and topics`() {
        Registry.bind(host)
        Hunting.register()
        val namespace = assertNotNull(fake.api.extensions.namespace("hunting"))
        assertEquals(listOf("hunters", "players/{player}", "few", "lair"), namespace.resources.map { it.path })
        val few = namespace.resources[2].params.single()
        assertEquals(listOf(1L, 10L, "3"), listOf(few.min, few.max, few.fallback))
        val params = namespace.resources[0].params
        assertEquals(listOf("limit" to "query", "sort" to "query"), params.map { it.name to it.where })
        assertEquals(listOf(1L, 50L, null), listOf(params[0].min, params[0].max, params[1].min))
        assertEquals(listOf("kills", "level"), params[1].choices)
        assertEquals("kills", params[1].fallback)
        assertEquals("path", namespace.resources[1].params.single().where)
        assertEquals("array", namespace.resources[0].returns!!.jsonObject["type"]!!.jsonPrimitive.content)

        val killed = assertNotNull(Topic.of("ext.hunting.boss.killed"))
        assertTrue(killed.recorded && killed.extension!!.public)
        assertEquals(setOf("place"), killed.extension!!.coordinateKeys)
        val data = killed.extension!!.data!!.jsonObject["properties"]!!.jsonObject
        assertEquals(listOf("killer", "helpers", "type", "level", "place"), data.keys.toList())
        assertEquals("integer", data["level"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("uuid", data["helpers"]!!.jsonObject["items"]!!.jsonObject["properties"]!!.jsonObject["uuid"]!!.jsonObject["format"]!!.jsonPrimitive.content)
    }

    @Test
    fun `resources read declared parameters and leave places out for visitors`() = testApplication {
        Registry.bind(host)
        Hunting.register()
        val hub = WebSocketHub(fake.ref::get, { null }) { _, _ -> emptyList() }
        application { HttpServer(fake.api, extensionRoutes, hub, Logger.getAnonymousLogger()).configure(this) }

        val two = Json.parseToJsonElement(client.get("/api/v1/extensions/hunting/hunters").bodyAsText()).jsonArray
        assertEquals("""{"player":{"uuid":"$steve","name":"Steve"},"kills":0}""", two[0].toString())
        assertEquals(50, Json.parseToJsonElement(client.get("/api/v1/extensions/hunting/hunters?limit=900").bodyAsText()).jsonArray.size)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/extensions/hunting/hunters?limit=five").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/extensions/hunting/hunters?sort=deaths").status)
        assertEquals(3, Json.parseToJsonElement(client.get("/api/v1/extensions/hunting/few").bodyAsText()).jsonArray.size)
        assertEquals(10, Json.parseToJsonElement(client.get("/api/v1/extensions/hunting/few?limit=900").bodyAsText()).jsonArray.size)

        assertEquals("""{"player":{"uuid":"$steve","name":"Steve"},"private":false}""", client.get("/api/v1/extensions/hunting/players/Steve").bodyAsText())
        assertEquals("""{"player":{"uuid":"$steve","name":"Steve"},"private":true}""", client.get("/api/v1/extensions/hunting/players/$steve") { bearerAuth(read) }.bodyAsText())
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/extensions/hunting/players/nobody").status)

        // Visitors don't see coordinates by default; the viewer group holds players:private.
        assertEquals("""{"name":"cave"}""", client.get("/api/v1/extensions/hunting/lair").bodyAsText())
        assertEquals("""{"name":"cave","place":{"world":"world","x":1.0,"y":64.0,"z":2.0}}""", client.get("/api/v1/extensions/hunting/lair") { bearerAuth(read) }.bodyAsText())

        val listed = Json.parseToJsonElement(client.get("/api/v1/extensions") { bearerAuth(read) }.bodyAsText()).jsonArray.single().jsonObject
        assertEquals("limit", listed["resources"]!!.jsonArray[0].jsonObject["params"]!!.jsonArray[0].jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("query", listed["resources"]!!.jsonArray[0].jsonObject["params"]!!.jsonArray[0].jsonObject["in"]!!.jsonPrimitive.content)
        assertTrue("properties" in listed["topics"]!!.jsonArray[0].jsonObject["data"]!!.jsonObject)
    }

    @Test
    fun `actions take a body, need their permission, and never answer visitors`() = testApplication {
        Registry.bind(host)
        Bosses.register()
        Careless.register()
        assertNull(fake.api.extensions.namespace("careless"), "an action without a permission refuses the expansion")
        val hub = WebSocketHub(fake.ref::get, { null }) { _, _ -> emptyList() }
        application { HttpServer(fake.api, extensionRoutes, hub, Logger.getAnonymousLogger()).configure(this) }
        val spawn = """{"type":"zombie","level":40,"at":{"world":"world","x":1,"y":64,"z":2}}"""
        fun io.ktor.client.request.HttpRequestBuilder.json(body: String) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }

        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/v1/extensions/bosses/bosses/spawn") { json(spawn) }.status)
        assertEquals(HttpStatusCode.Forbidden, client.post("/api/v1/extensions/bosses/bosses/spawn") { bearerAuth(read); json(spawn) }.status)
        val done = client.post("/api/v1/extensions/bosses/bosses/spawn") { bearerAuth(admin); json(spawn) }
        assertEquals(HttpStatusCode.OK, done.status)
        assertEquals("""{"type":"zombie","level":40,"at":{"world":"world","x":1.0,"y":64.0,"z":2.0}}""", done.bodyAsText())
        assertEquals(HttpStatusCode.BadRequest, client.post("/api/v1/extensions/bosses/bosses/spawn") { bearerAuth(admin); json("""{"at":{"world":"world","x":1,"y":64,"z":2}}""") }.status)
        assertEquals(HttpStatusCode.BadRequest, client.post("/api/v1/extensions/bosses/bosses/spawn") { bearerAuth(admin); json("""{"type":"zombie","at":{"world":"world","x":"far"}}""") }.status)
        assertEquals(HttpStatusCode.NotFound, client.post("/api/v1/extensions/bosses/nothing") { bearerAuth(admin); json("{}") }.status)

        assertEquals("""{"afk":"Steve","reason":"lunch"}""", client.post("/api/v1/extensions/bosses/players/Steve/afk") { bearerAuth(admin); json("""{"reason":"lunch"}""") }.bodyAsText())
        assertEquals("""{"back":"Steve"}""", client.delete("/api/v1/extensions/bosses/players/Steve/afk") { bearerAuth(admin) }.bodyAsText())
        assertEquals(HttpStatusCode.NotFound, client.delete("/api/v1/extensions/bosses/players/nobody/afk") { bearerAuth(admin) }.status)
        assertEquals(HttpStatusCode.NotFound, client.delete("/api/v1/extensions/bosses/bosses/spawn") { bearerAuth(admin) }.status, "a POST action isn't a DELETE")

        val listed = Json.parseToJsonElement(client.get("/api/v1/extensions") { bearerAuth(read) }.bodyAsText()).jsonArray.single { it.jsonObject["namespace"]!!.jsonPrimitive.content == "bosses" }.jsonObject
        val actions = listed["actions"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("POST bosses/spawn world:write", "POST players/{player}/afk players:moderate", "DELETE players/{player}/afk players:moderate"), actions.map { "${it["method"]!!.jsonPrimitive.content} ${it["path"]!!.jsonPrimitive.content} ${it["permission"]!!.jsonPrimitive.content}" })
        assertEquals(listOf("type body true", "level body false", "at body true"), actions[0]["params"]!!.jsonArray.map { it.jsonObject.let { p -> "${p["name"]!!.jsonPrimitive.content} ${p["in"]!!.jsonPrimitive.content} ${p["required"]!!.jsonPrimitive.content}" } })
        val anonymous = Json.parseToJsonElement(client.get("/api/v1/extensions/bosses/bosses/spawn").bodyAsText())
        assertTrue("error" in anonymous.jsonObject, "an action's path isn't a resource")
    }

    @Test
    fun `events go out with their player, world, and every player they name`() {
        val seen = mutableListOf<ApiEvent>()
        fake.bus.subscribe { seen += it }
        Registry.bind(host)
        Hunting.register()
        val data = BossKilled(PlayerRef(steve, "Steve"), listOf(PlayerRef(alex, "Alex")), "zombie", 40, Place("world_nether", 1.0, 2.0, 3.0))
        assertTrue(Hunting.killed.publish(data))
        val event = seen.single()
        assertEquals("ext.hunting.boss.killed", event.topic.id)
        assertEquals(steve.toString(), event.player?.uuid)
        assertEquals("world_nether", event.world)
        assertEquals(setOf(alex.toString()), event.others)
        assertEquals(40, event.data["level"]!!.jsonPrimitive.content.toInt())
        assertEquals("world_nether", event.data["place"]!!.jsonObject["world"]!!.jsonPrimitive.content)
        assertFalse(event.hidden)

        // A vanished helper hides the event as a vanished killer would.
        val vanished = Vanished().apply { add(alex) }
        val marked = mutableListOf<ApiEvent>()
        EventBus(fake.history, vanished) { false }.apply { subscribe { marked += it } }.publish(event)
        assertTrue(marked.single().hidden)
    }

    @Test
    fun `services files name objects and classes`() {
        val folder = Files.createTempDirectory("services")
        val file = folder.resolve(ExpansionServices.FILE)
        Files.createDirectories(file.parent)
        Files.writeString(file, "# Hunting\n${Hunting::class.java.name}\n${Plain::class.java.name}\nno.such.Expansion\n")
        val found = URLClassLoader(arrayOf(folder.toUri().toURL()), javaClass.classLoader).use { ExpansionServices.find(it) }
        assertEquals(3, found.size)
        assertTrue(found[0].expansion.getOrThrow() === Hunting)
        assertEquals("plain", found[1].expansion.getOrThrow().id())
        assertTrue(found[2].expansion.isFailure)
    }
}
