package com.mairwunnx.worldapi

import com.mairwunnx.worldapi.config.WebhookSettings
import com.mairwunnx.worldapi.config.WorldApiConfig
import com.mairwunnx.worldapi.connections.ChatLine
import com.mairwunnx.worldapi.connections.WebhookWizard
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WizardFlowTest {
    private val bodies = LinkedBlockingQueue<String>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/hook") { exchange ->
            bodies += exchange.requestBody.readBytes().decodeToString()
            exchange.sendResponseHeaders(204, -1)
            exchange.close()
        }
        start()
    }

    @Test
    fun `an operator connects Discord by buttons, and the pasted URL never goes to chat`() = TestApi(WorldApiConfig(webhooks = WebhookSettings(allowPrivateAddresses = true))).use { fake ->
        val replies = LinkedBlockingQueue<List<ChatLine>>()
        val wizard = WebhookWizard({ fake.api }, CoroutineScope(SupervisorJob() + Dispatchers.Default)) { _, lines -> replies += lines }
        val steve = UUID.randomUUID()
        fun step(vararg args: String) = wizard.command(steve, "Steve", args.toList()).let { replies.poll(5, TimeUnit.SECONDS)!! }
        fun buttons(lines: List<ChatLine>) = lines.filterIsInstance<ChatLine.Buttons>().flatMap { it.buttons }.mapNotNull { it.run }

        assertTrue("/worldapi webhook new discord" in buttons(step("new")))
        assertTrue("/worldapi webhook topics players" in buttons(step("new", "discord")))
        assertTrue("/worldapi webhook language ru" in buttons(step("topics", "players")))
        step("language", "ru")
        // Plain chat before the paste step isn't taken; the paste is.
        assertFalse(wizard.chat(UUID.randomUUID(), "Alex", "hello"))
        assertTrue(wizard.chat(steve, "Steve", "http://127.0.0.1:${server.address.port}/hook"))
        val done = replies.poll(10, TimeUnit.SECONDS)!!
        // Over http the wizard wants https; the operator pastes an https one next, which our test server can't serve, so allow http here.
        assertTrue(done.filterIsInstance<ChatLine.Text>().any { "https://" in it.text }, done.toString())
        assertTrue(wizard.chat(steve, "Steve", " https://127.0.0.1:${server.address.port}/hook "))
        val made = replies.poll(15, TimeUnit.SECONDS)!!
        assertTrue(made.filterIsInstance<ChatLine.Text>().first().text.startsWith("Webhook discord is ready"), made.toString())
        val stored = assertNotNull(fake.api.webhooks.store!!.find("discord")).hook
        assertEquals(listOf("player.join", "player.leave", "player.death", "player.advancement"), stored.topics)
        assertEquals("ru", stored.language)
        assertEquals("player:Steve", fake.api.webhooks.store!!.find("discord")!!.createdBy)
        // Once made, the wizard lets chat through again.
        assertFalse(wizard.chat(steve, "Steve", "https://example.com"))
        server.stop(0)
    }
}
