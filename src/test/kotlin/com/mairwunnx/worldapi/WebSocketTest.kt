package com.mairwunnx.worldapi

import com.mairwunnx.worldapi.api.HttpServer
import com.mairwunnx.worldapi.api.Scope
import com.mairwunnx.worldapi.config.Anonymous
import com.mairwunnx.worldapi.config.Auth
import com.mairwunnx.worldapi.config.Token
import com.mairwunnx.worldapi.config.WorldApiConfig
import com.mairwunnx.worldapi.events.ApiEvent
import com.mairwunnx.worldapi.events.Topic
import com.mairwunnx.worldapi.model.PlayerRef
import com.mairwunnx.worldapi.ws.WebSocketHub
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebSocketTest {
    private val token = "ws-token-0123456789"
    private val admin = "ws-admin-0123456789"
    private val config = WorldApiConfig(
        auth = Auth(
            tokens = listOf(Token("site", token, listOf(Scope.READ)), Token("ops", admin, listOf(Scope.ADMIN))),
            anonymous = Anonymous(endpoints = listOf("/ws")),
        ),
    )

    private fun test(block: suspend DefaultClientWebSocketSession.(WebSocketHub) -> Unit) = TestApi(config).use { fake ->
        lateinit var server: HttpServer
        val hub = WebSocketHub(fake.ref::get, { server.authenticate(it) }) { topic, _ ->
            if (topic == Topic.WORLD_TIME) listOf(ApiEvent(topic, buildJsonObject { put("dayTime", 6000) }, world = "world", subject = "world")) else emptyList()
        }
        server = HttpServer(fake.api, emptyList(), hub, Logger.getAnonymousLogger())
        testApplication {
            application { server.configure(this) }
            createClient { install(WebSockets) }.webSocket("/api/v1/ws") { block(hub) }
        }
    }

    private suspend fun DefaultClientWebSocketSession.next(): JsonObject = withTimeout(5_000) {
        while (true) {
            val message = Json.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject
            if (message["op"]!!.jsonPrimitive.content != "heartbeat") return@withTimeout message
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }

    private suspend fun DefaultClientWebSocketSession.say(text: String) = send(Frame.Text(text))

    private fun JsonObject.op() = this["op"]!!.jsonPrimitive.content

    private fun death(player: String) = ApiEvent(
        Topic.PLAYER_DEATH,
        buildJsonObject {
            put("message", "$player fell")
            put("location", buildJsonObject { put("x", 1) })
        },
        PlayerRef("uuid-$player", player), "world",
    )

    @Test
    fun `anonymous clients get a snapshot, then events`() = test { hub ->
        val hello = next()
        assertEquals("hello", hello.op())
        assertEquals("false", hello["authenticated"]!!.jsonPrimitive.content)
        say("""{"op":"subscribe","topics":["world.time","player.death"]}""")
        assertEquals("subscribed", next().op())
        val snapshot = next()
        assertEquals("snapshot", snapshot.op())
        assertEquals("world.time", snapshot["topic"]!!.jsonPrimitive.content)
        hub.accept(death("Steve"))
        val event = next()
        assertEquals("event", event.op())
        val data = event["event"]!!.jsonObject["data"]!!.jsonObject
        assertEquals("Steve fell", data["message"]!!.jsonPrimitive.content)
        // Anonymous clients don't see coordinates by default.
        assertFalse("location" in data)
    }

    @Test
    fun `filters narrow events to one player`() = test { hub ->
        next()
        say("""{"op":"auth","token":"$token"}""")
        assertEquals("authenticated", next().op())
        say("""{"op":"subscribe","topics":["player.death"],"filter":{"player":"alex"}}""")
        assertEquals("subscribed", next().op())
        hub.accept(death("Steve"))
        hub.accept(death("Alex"))
        val event = next()
        assertEquals("Alex", event["event"]!!.jsonObject["player"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertTrue("location" in event["event"]!!.jsonObject["data"]!!.jsonObject)
    }

    @Test
    fun `events about vanished players reach admin clients only`() {
        for ((login, sees) in listOf(null to false, token to false, admin to true)) test { hub ->
            next()
            if (login != null) {
                say("""{"op":"auth","token":"$login"}""")
                assertEquals("authenticated", next().op())
            }
            say("""{"op":"subscribe","topics":["player.death"]}""")
            assertEquals("subscribed", next().op())
            hub.accept(death("Ghost").copy(hidden = true))
            hub.accept(death("Steve"))
            val names = listOf(next()) + if (sees) listOf(next()) else emptyList()
            val expected = if (sees) listOf("Ghost", "Steve") else listOf("Steve")
            assertEquals(expected, names.map { it["event"]!!.jsonObject["player"]!!.jsonObject["name"]!!.jsonPrimitive.content }, "client ${login ?: "anonymous"}")
        }
    }

    @Test
    fun `forbidden and unknown topics are refused`() = test {
        next()
        say("""{"op":"subscribe","topics":["player.move"]}""")
        assertEquals("topic_forbidden", next()["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
        say("""{"op":"subscribe","topics":["player.dance"]}""")
        assertEquals("unknown_topic", next()["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
        say("""{"op":"auth","token":"wrong-token-012345"}""")
        assertEquals("invalid_token", next()["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
        say("""{"op":"ping"}""")
        assertEquals("pong", next().op())
    }
}
