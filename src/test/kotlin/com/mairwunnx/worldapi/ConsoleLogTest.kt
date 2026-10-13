package com.mairwunnx.worldapi

import com.mairwunnx.worldapi.api.HttpServer
import com.mairwunnx.worldapi.api.Scope
import com.mairwunnx.worldapi.config.Auth
import com.mairwunnx.worldapi.config.Limits
import com.mairwunnx.worldapi.config.Token
import com.mairwunnx.worldapi.config.WorldApiConfig
import com.mairwunnx.worldapi.core.ConsoleLog
import com.mairwunnx.worldapi.routes.serverRoutes
import com.mairwunnx.worldapi.ws.WebSocketHub
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConsoleLogTest {
    private val secret = "my-config-token-0123456789"

    @Test
    fun `tokens and colors never reach the buffer`() {
        val log = ConsoleLog({ 10 }) { listOf(secret) }
        val issued = "ab".repeat(32)
        log.add(0, "INFO", "Minecraft", "\u001B[33mtoken $secret and $issued, §aGreen")
        val line = log.read(null, null, 10).single().message
        assertEquals("token [token] and [redacted], Green", line)
        // UUIDs keep their dashes, so they stay readable.
        log.add(0, "INFO", null, "Steve is 0b8f6a3e-1c2d-4e5f-8a9b-0c1d2e3f4a5b")
        assertTrue("0b8f6a3e-1c2d-4e5f-8a9b-0c1d2e3f4a5b" in log.read(null, null, 1).single().message)
    }

    @Test
    fun `old lines fall out, and reads filter by level and text`() {
        val log = ConsoleLog({ 3 }) { emptyList() }
        log.add(0, "INFO", "Minecraft", "Steve joined the game")
        log.add(1, "WARN", "Minecraft", "Can't keep up!")
        log.add(2, "INFO", "GriefPrevention", "Steve claimed land")
        log.add(3, "ERROR", "Minecraft", "Steve's chunk failed to save")
        assertEquals(3, log.size)
        assertEquals(listOf("Can't keep up!", "Steve's chunk failed to save"), log.read("warn", null, 10).map { it.message })
        assertEquals(listOf("Steve claimed land", "Steve's chunk failed to save"), log.read(null, "steve", 10).map { it.message })
        assertEquals(listOf("Steve claimed land"), log.read(null, "griefprevention", 10).map { it.message })
        assertEquals(listOf("Steve's chunk failed to save"), log.read(null, null, 1).map { it.message })
    }

    @Test
    fun `only admin tokens read the console`() {
        val admin = "admin-token-0123456789"
        val read = "read-token-0123456789"
        val config = WorldApiConfig(
            auth = Auth(tokens = listOf(Token("admin", admin, listOf(Scope.ADMIN)), Token("site", read, listOf(Scope.READ)))),
            limits = Limits(consoleLines = 50),
        )
        TestApi(config).use { fake ->
            fake.api.console.add(0, "INFO", "Minecraft", "Done (3.2s)! uses $admin")
            val hub = WebSocketHub(fake.ref::get, { null }) { _, _ -> emptyList() }
            val routes = serverRoutes.filter { it.path == "/server/logs" }
            testApplication {
                application { HttpServer(fake.api, routes, hub, Logger.getAnonymousLogger()).configure(this) }
                assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/server/logs").status)
                assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/server/logs") { bearerAuth(read) }.status)
                val body = client.get("/api/v1/server/logs?level=info&grep=done") { bearerAuth(admin) }.bodyAsText()
                assertTrue("Done (3.2s)! uses [token]" in body, body)
                assertFalse(admin in body)
                assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/server/logs?limit=0") { bearerAuth(admin) }.status)
            }
        }
    }
}
