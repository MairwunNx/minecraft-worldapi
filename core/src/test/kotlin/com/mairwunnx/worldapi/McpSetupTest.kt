package com.mairwunnx.worldapi

import com.mairwunnx.worldapi.connections.McpSetup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpSetupTest {
    private val token = "ab".repeat(32)
    private val setup = McpSetup.build("https://example.com/worldapi/api/v1", token)

    @Test
    fun `every client gets a way in, for the machine and for one project`() {
        assertEquals(listOf("claude", "codex", "cursor", "opencode", "plain"), setup.clients.map { it.client }.distinct())
        val claude = setup.clients.filter { it.client == "claude" }
        assertEquals(listOf("user", "project"), claude.map { it.scope })
        assertTrue("--scope local" in claude[1].command!!)
        assertTrue("-e WORLDAPI_TOKEN=$token" in claude[0].command!!)
        val codex = setup.clients.single { it.client == "codex" && it.scope == "project" }
        assertEquals(".codex/config.toml", codex.file)
        assertTrue(codex.trust)
        assertTrue("WORLDAPI_URL = \"https://example.com/worldapi/api/v1\"" in codex.content!!)
    }

    @Test
    fun `files are valid JSON with the token, and project files ask for gitignore`() {
        for (client in setup.clients.filter { it.content != null && it.client != "codex" }) {
            val json = Json.parseToJsonElement(client.content!!).jsonObject
            assertTrue(token in json.toString(), client.client)
            if (client.scope == "project") assertEquals(client.file, client.gitignore)
        }
        val cursor = setup.clients.single { it.client == "cursor" && it.scope == "project" }
        assertEquals("npx", Json.parseToJsonElement(cursor.content!!).jsonObject["mcpServers"]!!.jsonObject["minecraft"]!!.jsonObject["command"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an address that could break a command is refused`() {
        for (url in listOf("https://a.com/x\" && rm -rf ~", "https://a.com/x y", "file:///etc/passwd", "https://a.com/\$(id)", "https://a.com/`id`")) {
            assertFalse(McpSetup.validUrl(url), url)
            assertFailsWith<IllegalArgumentException> { McpSetup.build(url, token) }
        }
        assertTrue(McpSetup.validUrl("http://[::1]:8110/api/v1"))
    }
}
