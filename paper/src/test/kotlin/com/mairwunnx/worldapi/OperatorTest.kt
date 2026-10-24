package com.mairwunnx.worldapi

import com.mairwunnx.worldapi.api.HttpServer
import com.mairwunnx.worldapi.config.Auth
import com.mairwunnx.worldapi.config.Token
import com.mairwunnx.worldapi.config.WorldApiConfig
import com.mairwunnx.worldapi.routes.accessRoutes
import com.mairwunnx.worldapi.routes.playerDataRoutes
import com.mairwunnx.worldapi.routes.serverRoutes
import com.mairwunnx.worldapi.ws.WebSocketHub
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.patch
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals

/** Operators run every command and /worldapi, which makes tokens with any permission: moderators can't make them. */
class OperatorTest {
    private val moderator = "moderator-token-0123456789"
    private val owner = "owner-token-0123456789"
    private val config = WorldApiConfig(
        auth = Auth(tokens = listOf(Token("mod", moderator, listOf("moderator")), Token("owner", owner, listOf("owner")))),
    )

    private fun code(body: String) = Json.parseToJsonElement(body).jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.content

    private fun test(block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) = TestApi(config).use { fake ->
        val hub = WebSocketHub(fake.ref::get, { null }) { _, _ -> emptyList() }
        val routes = accessRoutes + playerDataRoutes + serverRoutes.filter { it.path == "/server/commands" }
        testApplication {
            application { HttpServer(fake.api, routes, hub, Logger.getAnonymousLogger()).configure(this) }
            block()
        }
    }

    @Test
    fun `moderators can't make operators`() = test {
        val ops = client.patch("/api/v1/server/ops") { bearerAuth(moderator); contentType(ContentType.Application.Json); setBody("""{"add":["Steve"]}""") }
        assertEquals(HttpStatusCode.Forbidden, ops.status)
        assertEquals("missing_scope", code(ops.bodyAsText()))
        val permissions = client.patch("/api/v1/players/Steve/permissions") { bearerAuth(moderator); contentType(ContentType.Application.Json); setBody("""{"op":true}""") }
        assertEquals(HttpStatusCode.Forbidden, permissions.status)
        assertEquals("missing_scope", code(permissions.bodyAsText()))
    }
}
