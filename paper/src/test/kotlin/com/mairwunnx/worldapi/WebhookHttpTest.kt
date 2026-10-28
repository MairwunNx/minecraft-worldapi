package com.mairwunnx.worldapi

import com.mairwunnx.worldapi.api.HttpServer
import com.mairwunnx.worldapi.config.Auth
import com.mairwunnx.worldapi.config.Token
import com.mairwunnx.worldapi.config.Webhook
import com.mairwunnx.worldapi.config.WebhookSettings
import com.mairwunnx.worldapi.config.WorldApiConfig
import com.mairwunnx.worldapi.routes.webhookRoutes
import com.mairwunnx.worldapi.ws.WebSocketHub
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebhookHttpTest {
    private val admin = "admin-token-0123456789"
    private val hooksOnly = "hooks-token-0123456789"

    private val config = WorldApiConfig(
        auth = Auth(tokens = listOf(Token("admin", admin, listOf("admin")), Token("hooks", hooksOnly, listOf("webhooks:manage", "events:read")))),
        webhooks = WebhookSettings(hooks = listOf(Webhook("owned", url = "https://example.com/hook", topics = listOf("*")))),
    )

    private fun test(block: suspend ApplicationTestBuilder.(TestApi) -> Unit) = TestApi(config).use { fake ->
        val hub = WebSocketHub(fake.ref::get, { null }) { _, _ -> emptyList() }
        testApplication {
            application { HttpServer(fake.api, webhookRoutes, hub, Logger.getAnonymousLogger()).configure(this) }
            block(fake)
        }
    }

    private fun code(body: String) = Json.parseToJsonElement(body).jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.content

    private suspend fun ApplicationTestBuilder.make(token: String, body: String) = client.post("/api/v1/webhooks") {
        bearerAuth(token)
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    @Test
    fun `a webhook gets no more than its maker has`() = test {
        val tooMuch = make(hooksOnly, """{"name":"spy","format":"discord","url":"https://1.1.1.1/hook","topics":["player.chat"],"permissions":["chat:read"],"test":false}""")
        assertEquals(HttpStatusCode.Forbidden, tooMuch.status)
        assertEquals("cannot_grant", code(tooMuch.bodyAsText()))
        // Without permissions it gets the safe default, which the maker must hold too: this one lacks server:read.
        assertEquals(HttpStatusCode.Forbidden, make(hooksOnly, """{"name":"d","format":"discord","url":"https://1.1.1.1/hook","topics":["*"],"test":false}""").status)
        val fits = make(hooksOnly, """{"name":"d","format":"discord","url":"https://1.1.1.1/hook","topics":["*"],"permissions":["events:read"],"test":false}""")
        assertEquals(HttpStatusCode.Created, fits.status, fits.bodyAsText())
    }

    @Test
    fun `it can't post to this machine or a private network`() = test {
        for (url in listOf("http://127.0.0.1:8110/api/v1", "http://169.254.169.254/latest", "http://192.168.1.1/", "http://localhost/")) {
            val refused = make(admin, """{"name":"inside","format":"json","url":"$url","topics":["*"],"test":false}""")
            assertEquals(HttpStatusCode.BadRequest, refused.status, url)
            assertEquals("private_address", code(refused.bodyAsText()))
        }
    }

    @Test
    fun `a json webhook's secret shows once, and the list hides its address`() = test {
        val made = make(admin, """{"name":"site","format":"json","url":"https://1.1.1.1/secret-path/abc","topics":["player.*"],"test":false}""")
        assertEquals(HttpStatusCode.Created, made.status, made.bodyAsText())
        val secret = Json.parseToJsonElement(made.bodyAsText()).jsonObject["secret"]!!.jsonPrimitive.content
        assertTrue(secret.length >= 32)
        val list = client.get("/api/v1/webhooks") { bearerAuth(admin) }.bodyAsText()
        assertFalse(secret in list)
        assertFalse("abc" in list)
        val names = Json.parseToJsonElement(list).jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content to it.jsonObject["source"]!!.jsonPrimitive.content }
        assertEquals(listOf("owned" to "config", "site" to "made"), names)
    }

    @Test
    fun `config's webhooks change only there, and names stay unique`() = test {
        assertEquals(HttpStatusCode.Conflict, make(admin, """{"name":"owned","format":"discord","url":"https://1.1.1.1/x","topics":["*"],"test":false}""").status)
        assertEquals(HttpStatusCode.BadRequest, client.delete("/api/v1/webhooks/owned") { bearerAuth(admin) }.status)
        val paused = client.patch("/api/v1/webhooks/owned") { bearerAuth(admin); contentType(ContentType.Application.Json); setBody("""{"enabled":false}""") }
        assertEquals("config_webhook", code(paused.bodyAsText()))
    }

    @Test
    fun `a weaker admin can't change or delete a stronger one's webhook`() = test {
        make(admin, """{"name":"chat","format":"discord","url":"https://1.1.1.1/x","topics":["player.chat"],"permissions":["events:read","chat:read"],"test":false}""")
        assertEquals(HttpStatusCode.Forbidden, client.delete("/api/v1/webhooks/chat") { bearerAuth(hooksOnly) }.status)
        val patch = client.patch("/api/v1/webhooks/chat") { bearerAuth(hooksOnly); contentType(ContentType.Application.Json); setBody("""{"topics":["*"]}""") }
        assertEquals(HttpStatusCode.Forbidden, patch.status)
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/webhooks/chat") { bearerAuth(admin) }.status)
    }
}
