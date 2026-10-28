package com.mairwunnx.worldapi

import com.mairwunnx.worldapi.config.ConfigFile
import com.mairwunnx.worldapi.config.Events
import com.mairwunnx.worldapi.config.Webhook
import com.mairwunnx.worldapi.config.WebhookFormat
import com.mairwunnx.worldapi.config.WebhookSettings
import com.mairwunnx.worldapi.config.WorldApiConfig
import com.mairwunnx.worldapi.events.ApiEvent
import com.mairwunnx.worldapi.events.Topic
import com.mairwunnx.worldapi.model.PlayerRef
import com.mairwunnx.worldapi.routes.masked
import com.mairwunnx.worldapi.webhooks.Addresses
import com.mairwunnx.worldapi.webhooks.Formats
import com.mairwunnx.worldapi.webhooks.Messages
import com.mairwunnx.worldapi.webhooks.Webhooks
import kotlinx.serialization.json.jsonPrimitive
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.net.InetSocketAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.logging.Logger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebhookTest {
    private class Received(val body: String, val headers: Map<String, String>)

    private val received = LinkedBlockingQueue<Received>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/hook") { exchange ->
            val headers = exchange.requestHeaders.mapValues { it.value.first() }.mapKeys { it.key.lowercase() }
            received += Received(exchange.requestBody.readBytes().decodeToString(), headers)
            exchange.sendResponseHeaders(204, -1)
            exchange.close()
        }
        start()
    }
    private var webhooks: Webhooks? = null

    @AfterTest
    fun stop() {
        webhooks?.close()
        server.stop(0)
    }

    private fun send(hook: Webhook, event: ApiEvent): Received? {
        val config = WorldApiConfig(webhooks = WebhookSettings(allowPrivateAddresses = true, hooks = listOf(hook)))
        val sink = Webhooks({ config }, "test", Logger.getAnonymousLogger()).also { webhooks = it }
        assertTrue(sink.wants(event.topic))
        sink.accept(event)
        return received.poll(5, TimeUnit.SECONDS)
    }

    private val url get() = "http://127.0.0.1:${server.address.port}/hook"

    private val death = ApiEvent(Topic.PLAYER_DEATH, buildJsonObject {
        put("cause", "fall")
        putJsonObject("location") { put("x", 10) }
    })

    @Test
    fun `an event arrives signed, and the signature checks out`() {
        val got = assertNotNull(send(Webhook("deaths", url = url, topics = listOf("player.death"), permissions = listOf("viewer"), secret = "s3cret-s3cret-s3cret"), death))
        assertTrue("\"cause\":\"fall\"" in got.body)
        assertTrue("\"location\"" in got.body)
        assertEquals("player.death", got.headers["x-worldapi-topic"])
        val at = got.headers.getValue("x-worldapi-timestamp")
        val delivery = got.headers.getValue("x-worldapi-delivery")
        assertEquals("sha256=" + Webhooks.sign("s3cret-s3cret-s3cret", "$at.$delivery.${got.body}"), got.headers["x-worldapi-signature"])
    }

    @Test
    fun `by default, coordinates are stripped`() {
        val got = assertNotNull(send(Webhook("deaths", url = url, topics = listOf("player.death")), death))
        assertTrue("\"cause\":\"fall\"" in got.body)
        assertFalse("\"location\"" in got.body)
    }

    @Test
    fun `chat needs chat read`() {
        val chat = ApiEvent(Topic.PLAYER_CHAT, buildJsonObject { put("message", "hi") })
        fun deliver(permissions: List<String>): Received? {
            val config = WorldApiConfig(events = Events(chat = true), webhooks = WebhookSettings(hooks = listOf(Webhook("chat", url = url, topics = listOf("player.chat"), permissions = permissions))))
            webhooks?.close()
            webhooks = Webhooks({ config }, "test", Logger.getAnonymousLogger()).also { it.accept(chat) }
            return received.poll(2, TimeUnit.SECONDS)
        }
        assertNull(deliver(listOf("events:read")))
        assertNotNull(deliver(listOf("events:read", "chat:read")))
    }

    @Test
    fun `a webhook without a valid url or topics doesn't load`() {
        assertFailsWith<IllegalArgumentException> { Webhook("x", url = "ftp://example.com", topics = listOf("player.join")) }
        assertFailsWith<IllegalArgumentException> { Webhook("x", url = "https://example.com", topics = emptyList()) }
        assertFailsWith<IllegalArgumentException> { Webhook("bad name", url = "https://example.com", topics = listOf("player.join")) }
    }

    @Test
    fun `duplicate names, unknown permissions or topics, and weak secrets don't load`() {
        fun load(hooks: String) = ConfigFile.parse("webhooks:\n  hooks:\n$hooks")
        val hook = "    - {name: a, url: \"https://example.com\", topics: [player.join]"
        load("$hook}")
        assertFailsWith<IllegalArgumentException> { load("$hook}\n$hook}") }
        assertFailsWith<IllegalArgumentException> { load("$hook, permissions: [players:fly]}") }
        assertFailsWith<IllegalArgumentException> { load("    - {name: a, url: \"https://example.com\", topics: [player.jump]}") }
        load("    - {name: a, url: \"https://example.com\", topics: [ext.mobosses.boss.spawned, \"player.*\", \"*\"]}")
        assertFailsWith<IllegalArgumentException> { load("$hook, secret: short}") }
        assertFailsWith<IllegalArgumentException> { load("$hook, secret: ${ConfigFile.WEBHOOK_SECRET_EXAMPLE}}") }
        assertFailsWith<IllegalArgumentException> { load("$hook, secret: \"\${HOOK_SECRET_UNSET}\"}") }
    }

    @Test
    fun `plain http warns, except on this machine`() {
        assertTrue(Webhooks.plain("http://example.com/hook"))
        assertFalse(Webhooks.plain("http://127.0.0.1:8080/hook"))
        assertFalse(Webhooks.plain("http://[::1]/hook"))
        assertFalse(Webhooks.plain("https://example.com/hook"))
    }

    @Test
    fun `a discord webhook gets a message in its language that mentions no one`() {
        val join = ApiEvent(Topic.PLAYER_JOIN, buildJsonObject { put("firstJoin", true) }, player = PlayerRef("1", "Steve"))
        val hook = Webhook("discord", WebhookFormat.DISCORD, url = url, topics = listOf("*"), language = "ru")
        val got = assertNotNull(send(hook, join))
        assertTrue("Steve впервые зашёл на сервер" in got.body, got.body)
        assertTrue("\"allowed_mentions\":{\"parse\":[]}" in got.body, got.body)
        assertNull(got.headers["x-worldapi-signature"])
    }

    @Test
    fun `its own words replace the default, and a stripped location never comes back through them`() {
        val death = ApiEvent(Topic.PLAYER_DEATH, buildJsonObject {
            put("message", "Steve fell")
            putJsonObject("location") { put("x", 10) }
        }, player = PlayerRef("1", "Steve"))
        assertEquals("RIP Steve fell", Messages.text(death, "en", mapOf("player.death" to "RIP {message}")))
        assertEquals("Steve joined the server", Messages.text(ApiEvent(Topic.PLAYER_JOIN, buildJsonObject {}, player = PlayerRef("1", "Steve")), "en"))
        // A custom message naming a value the event lacks falls back to the plain one.
        assertEquals("💀 Steve fell", Messages.text(death, "en", mapOf("player.death" to "{location} {message}")))
        assertNull(Messages.text(ApiEvent(Topic.SERVER_STATUS, buildJsonObject { put("status", "saved") }), "de"))
        assertEquals("✅ Der Server ist gestartet", Messages.text(ApiEvent(Topic.SERVER_STATUS, buildJsonObject { put("status", "started") }), "de"))
    }

    @Test
    fun `slack and google chat can't be made to mention everyone`() {
        val hook = Webhook("s", WebhookFormat.SLACK, url = "https://hooks.slack.com/services/x", topics = listOf("*"))
        assertEquals("&lt;!channel&gt; hi &amp; bye", Formats.message(hook, "<!channel> hi & bye")["text"]!!.jsonPrimitive.content)
        val telegram = Webhook("t", WebhookFormat.TELEGRAM, botToken = "123456:" + "a".repeat(35), chatId = "-100123", topics = listOf("*"))
        assertEquals("-100123", Formats.message(telegram, "hi")["chat_id"]!!.jsonPrimitive.content)
        assertNull(Formats.message(telegram, "hi")["parse_mode"])
    }

    @Test
    fun `star takes the notable topics, a prefix takes its own, and a paused webhook takes none`() {
        val all = Webhook("a", url = "https://example.com", topics = listOf("*"))
        assertTrue(all.listens(Topic.PLAYER_DEATH))
        assertFalse(all.listens(Topic.PLAYER_MOVE))
        assertFalse(all.listens(Topic.BLOCK_BREAK))
        val players = all.copy(topics = listOf("player.*"))
        assertTrue(players.listens(Topic.PLAYER_DAMAGE))
        assertFalse(players.listens(Topic.RAID_START))
        assertFalse(all.copy(enabled = false).listens(Topic.PLAYER_DEATH))
        // A pattern skips sampled values and players' commands; naming them still works, but not for sampled ones in config.
        assertFalse(players.listens(Topic.PLAYER_HEALTH))
        assertFalse(players.listens(Topic.PLAYER_COMMAND))
        assertTrue(all.copy(topics = listOf("player.command")).listens(Topic.PLAYER_COMMAND))
        assertFalse(ConfigFile.knownTopic("player.move"))
    }

    @Test
    fun `private addresses are refused, public ones pass`() {
        assertNotNull(Addresses.refusal("http://127.0.0.1:8110/api"))
        assertNotNull(Addresses.refusal("http://169.254.169.254/latest/meta-data"))
        assertNotNull(Addresses.refusal("http://[::1]/"))
        assertNotNull(Addresses.refusal("http://10.1.2.3/"))
        assertNotNull(Addresses.refusal("http://100.64.0.1/"))
        assertNotNull(Addresses.refusal("http://[::ffff:192.168.0.1]/"))
        assertNull(Addresses.refusal("https://1.1.1.1/"))
        // Plain http could be pointed inside between the check and the connection; https meets a certificate that isn't its own.
        assertNotNull(Addresses.refusal("http://1.1.1.1/"))
        for (inside in listOf("192.0.0.192", "198.18.0.1", "240.0.0.1", "255.255.255.255", "[64:ff9b::a00:5]", "[2002:a00:5::]", "[2001:0:4136:e378::1]", "[::10.0.0.5]")) {
            assertNotNull(Addresses.refusal("https://$inside/"), inside)
        }
        assertNull(Addresses.refusal("https://[64:ff9b::101:101]/"))
    }

    @Test
    fun `a made webhook to a private address doesn't go out, unless config allows it`() {
        val config = WorldApiConfig(webhooks = WebhookSettings(allowPrivateAddresses = false))
        val hook = Webhook("local", url = url, topics = listOf("player.death"))
        val sink = Webhooks({ config }, "test", Logger.getAnonymousLogger()).also { webhooks = it }
        val outcome = kotlinx.coroutines.runBlocking { sink.test(hook, fromConfig = false) }
        assertFalse(outcome.ok)
        assertTrue(outcome.refused)
        assertNull(received.poll(500, TimeUnit.MILLISECONDS))
        assertTrue(kotlinx.coroutines.runBlocking { sink.test(hook, fromConfig = true) }.ok)
    }

    @Test
    fun `the address shows without its secret part`() {
        assertEquals("https://discord.com/api/…", masked(Webhook("d", WebhookFormat.DISCORD, url = "https://discord.com/api/webhooks/1/SECRET", topics = listOf("*"))))
        assertEquals("https://ntfy.sh/…", masked(Webhook("n", url = "https://ntfy.sh/secret-topic", topics = listOf("*"))))
        assertEquals("Telegram chat -100123", masked(Webhook("t", WebhookFormat.TELEGRAM, botToken = "123456:" + "a".repeat(35), chatId = "-100123", topics = listOf("*"))))
    }

    @Test
    fun `Discord and Teams get no Markdown links from game text`() {
        val hook = Webhook("d", WebhookFormat.DISCORD, url = "https://discord.com/api/webhooks/1/x", topics = listOf("*"))
        val content = Formats.message(hook, "Steve was slain by [free op](https://evil.example)")["content"]!!.jsonPrimitive.content
        assertFalse("](" in content)
        assertTrue("free op" in content)
    }

    @Test
    fun `a webhook is tested once every 10 seconds`() {
        val sink = Webhooks({ WorldApiConfig() }, "test", Logger.getAnonymousLogger()).also { webhooks = it }
        assertTrue(sink.mayTest("a"))
        assertFalse(sink.mayTest("a"))
        assertTrue(sink.mayTest("b"))
    }
}
