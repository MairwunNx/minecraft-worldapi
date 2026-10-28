package com.mairwunnx.worldapi

import com.mairwunnx.worldapi.connections.ChatLine
import com.mairwunnx.worldapi.connections.McpChat
import com.mairwunnx.worldapi.config.Http
import com.mairwunnx.worldapi.config.WorldApiConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WizardTest {
    @Test
    fun `an MCP connection copies a setup per client, pointing at the public address when there is one`() {
        val token = "cd".repeat(32)
        val local = McpChat.lines("agent", "viewer", token, WorldApiConfig())
        val copies = local.filterIsInstance<ChatLine.Buttons>().flatMap { it.buttons }.mapNotNull { it.copy }
        assertEquals(9, copies.size)
        assertTrue(copies.all { token in it })
        assertTrue(copies.first().contains("http://localhost:8110/api/v1"))
        assertTrue(local.any { it is ChatLine.Text && "http.publicUrl" in it.text })
        val public = McpChat.lines("agent", "viewer", token, WorldApiConfig(http = Http(publicUrl = "https://mc.example.com/worldapi/api/v1/")))
        assertTrue(public.filterIsInstance<ChatLine.Buttons>().first().buttons.first().copy!!.contains("https://mc.example.com/worldapi/api/v1 "))
        assertTrue(public.none { it is ChatLine.Text && "http.publicUrl" in it.text })
    }
}
