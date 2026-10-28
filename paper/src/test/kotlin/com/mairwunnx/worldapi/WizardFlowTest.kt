package com.mairwunnx.worldapi

import com.mairwunnx.worldapi.config.WebhookSettings
import com.mairwunnx.worldapi.config.WorldApiConfig
import com.mairwunnx.worldapi.connections.ChatLine
import com.mairwunnx.worldapi.connections.SecretPrompt
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
    fun `an operator connects Discord by buttons, and the URL comes through a dialog, never chat`() = TestApi(WorldApiConfig(webhooks = WebhookSettings(allowPrivateAddresses = true))).use { fake ->
        val replies = LinkedBlockingQueue<List<ChatLine>>()
        val prompts = LinkedBlockingQueue<SecretPrompt>()
        val wizard = WebhookWizard({ fake.api }, CoroutineScope(SupervisorJob() + Dispatchers.Default), { _, lines -> replies += lines }, { _, prompt -> prompts += prompt })
        val steve = UUID.randomUUID()
        fun step(vararg args: String) = wizard.command(steve, "Steve", args.toList()).let { replies.poll(5, TimeUnit.SECONDS)!! }
        fun buttons(lines: List<ChatLine>) = lines.filterIsInstance<ChatLine.Buttons>().flatMap { it.buttons }.mapNotNull { it.run }

        assertTrue("/worldapi webhook new discord" in buttons(step("new")))
        assertTrue("/worldapi webhook topics players" in buttons(step("new", "discord")))
        assertTrue("/worldapi webhook language ru" in buttons(step("topics", "players")))
        assertTrue("/worldapi webhook ask" in buttons(step("language", "ru")))
        val prompt = assertNotNull(prompts.poll(1, TimeUnit.SECONDS))
        // A stale or forged answer fills nothing.
        wizard.answer(steve, "Steve", "not-the-nonce", "https://example.com/hook")
        assertTrue(replies.poll(5, TimeUnit.SECONDS)!!.filterIsInstance<ChatLine.Text>().single().text.contains("out of date"))
        // Plain http isn't a webhook URL; the window opens again.
        wizard.answer(steve, "Steve", prompt.nonce, "http://127.0.0.1:${server.address.port}/hook")
        assertTrue("/worldapi webhook ask" in buttons(replies.poll(5, TimeUnit.SECONDS)!!))
        wizard.answer(steve, "Steve", prompt.nonce, " https://127.0.0.1:${server.address.port}/hook ")
        val made = replies.poll(15, TimeUnit.SECONDS)!!
        assertTrue(made.filterIsInstance<ChatLine.Text>().first().text.startsWith("Webhook discord is ready"), made.toString())
        val stored = assertNotNull(fake.api.webhooks.store!!.find("discord")).hook
        assertEquals(listOf("player.join", "player.leave", "player.death", "player.advancement"), stored.topics)
        assertEquals("ru", stored.language)
        assertEquals("player:Steve", fake.api.webhooks.store!!.find("discord")!!.createdBy)
        // Once made, the same window can't make another.
        wizard.answer(steve, "Steve", prompt.nonce, "https://example.com/hook")
        assertTrue(replies.poll(5, TimeUnit.SECONDS)!!.filterIsInstance<ChatLine.Text>().single().text.contains("out of date"))
        server.stop(0)
    }
}
