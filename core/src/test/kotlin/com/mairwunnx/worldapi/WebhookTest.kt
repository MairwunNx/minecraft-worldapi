package com.mairwunnx.worldapi

import com.mairwunnx.worldapi.config.Events
import com.mairwunnx.worldapi.config.Webhook
import com.mairwunnx.worldapi.config.WorldApiConfig
import com.mairwunnx.worldapi.events.ApiEvent
import com.mairwunnx.worldapi.events.Topic
import com.mairwunnx.worldapi.events.Webhooks
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
        val config = WorldApiConfig(webhooks = listOf(hook))
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
        val got = assertNotNull(send(Webhook("deaths", url, listOf("player.death"), secret = "s3cret"), death))
        assertTrue("\"cause\":\"fall\"" in got.body)
        assertTrue("\"location\"" in got.body)
        assertEquals("player.death", got.headers["x-worldapi-topic"])
        val at = got.headers.getValue("x-worldapi-timestamp")
        assertEquals("sha256=" + Webhooks.sign("s3cret", "$at.${got.body}"), got.headers["x-worldapi-signature"])
    }

    @Test
    fun `without players private, coordinates are stripped`() {
        val got = assertNotNull(send(Webhook("deaths", url, listOf("player.death"), permissions = listOf("events:read")), death))
        assertTrue("\"cause\":\"fall\"" in got.body)
        assertFalse("\"location\"" in got.body)
    }

    @Test
    fun `chat needs chat read`() {
        val chat = ApiEvent(Topic.PLAYER_CHAT, buildJsonObject { put("message", "hi") })
        fun deliver(permissions: List<String>): Received? {
            val config = WorldApiConfig(events = Events(chat = true), webhooks = listOf(Webhook("chat", url, listOf("player.chat"), permissions)))
            webhooks?.close()
            webhooks = Webhooks({ config }, "test", Logger.getAnonymousLogger()).also { it.accept(chat) }
            return received.poll(2, TimeUnit.SECONDS)
        }
        assertNull(deliver(listOf("events:read")))
        assertNotNull(deliver(listOf("events:read", "chat:read")))
    }

    @Test
    fun `a webhook without a valid url or topics doesn't load`() {
        assertFailsWith<IllegalArgumentException> { Webhook("x", "ftp://example.com", listOf("player.join")) }
        assertFailsWith<IllegalArgumentException> { Webhook("x", "https://example.com", emptyList()) }
        assertFailsWith<IllegalArgumentException> { Webhook("bad name", "https://example.com", listOf("player.join")) }
    }
}
