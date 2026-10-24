package com.mairwunnx.worldapi

import com.mairwunnx.worldapi.api.HttpServer
import com.mairwunnx.worldapi.api.Scope
import com.mairwunnx.worldapi.api.endpoints
import com.mairwunnx.worldapi.api.notFound
import com.mairwunnx.worldapi.api.string
import com.mairwunnx.worldapi.config.Anonymous
import com.mairwunnx.worldapi.config.Auth
import com.mairwunnx.worldapi.config.RateLimit
import com.mairwunnx.worldapi.config.RateLimits
import com.mairwunnx.worldapi.config.Token
import com.mairwunnx.worldapi.config.WorldApiConfig
import com.mairwunnx.worldapi.store.Audit
import com.mairwunnx.worldapi.ws.WebSocketHub
import io.ktor.client.request.bearerAuth
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
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.selectAll

@Serializable
data class Echo(val name: String, val count: Int = 1)

class HttpTest {
    private val read = "read-token-0123456789"
    private val world = "world-token-0123456789"
    private val empty = "empty-token-0123456789"

    private val config = WorldApiConfig(
        auth = Auth(
            tokens = listOf(
                Token("site", read, listOf("viewer")), Token("builder", world, listOf("world:write")), Token("nothing", empty, emptyList()),
                Token("anonymous", "named-anonymous-0123456789", listOf("viewer")),
            ),
            anonymous = Anonymous(endpoints = listOf("/public", "/ws")),
        ),
        rateLimit = RateLimits(anonymous = RateLimit(60, 3)),
    )

    private val routes = endpoints("Test", Scope.SERVER_READ) {
        get("/public", "Anyone may read this") { Echo("public") }
        get("/private", "Needs a token") { Echo("private") }
        get("/query", "Reads a query parameter", query = listOf(string("name", "A name"))) { Echo(query("name") ?: "none") }
        post<Echo, Echo>("/echo", "Echoes the body", Scope.WORLD_WRITE) { body ->
            auditTarget = body.name
            body.copy(count = body.count + 1)
        }
        patch<Echo, Echo>("/admin", "Needs admin", Scope.SERVER_CONTROL) { it }
        get<Echo>("/missing", "Always 404") { notFound("thing_not_found", "No thing here") }
        get<Echo>("/broken", "Always fails") { error("boom") }
    }

    private fun test(block: suspend ApplicationTestBuilder.(TestApi) -> Unit) = TestApi(config).use { fake ->
        val hub = WebSocketHub(fake.ref::get, { null }) { _, _ -> emptyList() }
        testApplication {
            application { HttpServer(fake.api, routes, hub, Logger.getAnonymousLogger()).configure(this) }
            block(fake)
        }
    }

    private fun code(body: String) = Json.parseToJsonElement(body).jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.content

    @Test
    fun `anonymous clients read whitelisted paths only`() = test {
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/public").status)
        val denied = client.get("/api/v1/private")
        assertEquals(HttpStatusCode.Unauthorized, denied.status)
        assertEquals("token_required", code(denied.bodyAsText()))
        assertEquals("""{"name":"private","count":1}""", client.get("/api/v1/private") { bearerAuth(read) }.bodyAsText())
    }

    @Test
    fun `writes need a token with the scope, even on public paths`() = test {
        val body = """{"name":"stone"}"""
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/v1/echo") { contentType(ContentType.Application.Json); setBody(body) }.status)
        val forbidden = client.post("/api/v1/echo") { bearerAuth(read); contentType(ContentType.Application.Json); setBody(body) }
        assertEquals(HttpStatusCode.Forbidden, forbidden.status)
        assertEquals("missing_scope", code(forbidden.bodyAsText()))
        val ok = client.post("/api/v1/echo") { bearerAuth(world); contentType(ContentType.Application.Json); setBody(body) }
        assertEquals("""{"name":"stone","count":2}""", ok.bodyAsText())
        assertEquals(HttpStatusCode.Forbidden, client.patch("/api/v1/admin") { bearerAuth(world); setBody(body) }.status)
    }

    @Test
    fun `unknown tokens are rejected, not treated as anonymous`() = test {
        val response = client.get("/api/v1/public") { bearerAuth("nope-nope-nope-nope") }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals("invalid_token", code(response.bodyAsText()))
    }

    @Test
    fun `bodies are validated strictly`() = test {
        suspend fun post(body: String) = client.post("/api/v1/echo") { bearerAuth(world); contentType(ContentType.Application.Json); setBody(body) }
        val unknown = post("""{"name":"a","colour":"red"}""")
        assertEquals(HttpStatusCode.BadRequest, unknown.status)
        assertEquals("invalid_body", code(unknown.bodyAsText()))
        assertEquals(HttpStatusCode.BadRequest, post("""{"name":"a","count":"many"}""").status)
        assertEquals(HttpStatusCode.BadRequest, post("""{"count":1}""").status)
        assertEquals(HttpStatusCode.BadRequest, post("""not json""").status)
        assertEquals(HttpStatusCode.BadRequest, post("").status)
    }

    @Test
    fun `errors share one shape`() = test {
        val missing = client.get("/api/v1/missing") { bearerAuth(read) }
        assertEquals(HttpStatusCode.NotFound, missing.status)
        assertEquals("""{"error":{"code":"thing_not_found","message":"No thing here"}}""", missing.bodyAsText())
        assertEquals("not_found", code(client.get("/api/v1/nowhere") { bearerAuth(read) }.bodyAsText()))
        val broken = client.get("/api/v1/broken") { bearerAuth(read) }
        assertEquals(HttpStatusCode.InternalServerError, broken.status)
        assertEquals("internal_error", code(broken.bodyAsText()))
    }

    @Test
    fun `oversized bodies are refused before they're read`() = test {
        val big = """{"name":"${"x".repeat(2_000_000)}"}"""
        val response = client.post("/api/v1/echo") { bearerAuth(world); contentType(ContentType.Application.Json); setBody(big) }
        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        assertEquals("body_too_large", code(response.bodyAsText()))
    }

    @Test
    fun `query parameters reach the handler`() = test {
        assertEquals("""{"name":"Steve","count":1}""", client.get("/api/v1/query?name=Steve") { bearerAuth(read) }.bodyAsText())
    }

    @Test
    fun `anonymous clients hit the rate limit`() = test {
        repeat(3) { assertEquals(HttpStatusCode.OK, client.get("/api/v1/public").status) }
        val limited = client.get("/api/v1/public")
        assertEquals(HttpStatusCode.TooManyRequests, limited.status)
        assertTrue(limited.headers["Retry-After"]!!.toInt() >= 1)
        // Token holders have their own bucket.
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/public") { bearerAuth(read) }.status)
    }

    @Test
    fun `writes land in the audit log`() = test { fake ->
        client.post("/api/v1/echo") { bearerAuth(world); contentType(ContentType.Application.Json); setBody("""{"name":"stone"}""") }
        client.post("/api/v1/echo") { bearerAuth(read); contentType(ContentType.Application.Json); setBody("""{"name":"dirt"}""") }
        delay(200)
        val rows = fake.store.read {
            Audit.selectAll().orderBy(Audit.id).map { "${it[Audit.token]} ${it[Audit.method]} ${it[Audit.target]} ${it[Audit.status]}" }
        }
        assertEquals(listOf("builder POST stone 200"), rows)
    }

    @Test
    fun `the OpenAPI document and docs page are served`() = test {
        val doc = client.get("/api/v1/openapi.json") { bearerAuth(read) }
        assertEquals(HttpStatusCode.OK, doc.status)
        val paths = Json.parseToJsonElement(doc.bodyAsText()).jsonObject["paths"]!!.jsonObject
        assertTrue("/echo" in paths && "/ws" in paths)
        assertTrue("api-reference" in client.get("/api/v1/docs") { bearerAuth(read) }.bodyAsText())
    }

    @Test
    fun `a token without permissions gets nothing, not everything`() = test {
        val body = """{"name":"stone"}"""
        val write = client.post("/api/v1/echo") { bearerAuth(empty); contentType(ContentType.Application.Json); setBody(body) }
        assertEquals(HttpStatusCode.Forbidden, write.status)
        assertEquals("missing_scope", code(write.bodyAsText()))
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/private") { bearerAuth(empty) }.status)
        assertEquals(HttpStatusCode.Forbidden, client.patch("/api/v1/admin") { bearerAuth(empty); setBody(body) }.status)
    }

    @Test
    fun `a token named anonymous is still a token`() = test {
        val me = client.get("/api/v1/private") { bearerAuth("named-anonymous-0123456789") }
        assertEquals(HttpStatusCode.OK, me.status)
        // Anonymous clients share the IP's bucket; this token has its own and keeps going after it.
        repeat(5) { assertEquals(HttpStatusCode.OK, client.get("/api/v1/public") { bearerAuth("named-anonymous-0123456789") }.status) }
    }

    @Test
    fun `the docs page pins its script`() = test {
        val docs = client.get("/api/v1/docs") { bearerAuth(read) }
        assertTrue("integrity=\"sha384-" in docs.bodyAsText())
        assertTrue("frame-ancestors 'none'" in docs.headers["Content-Security-Policy"].orEmpty())
    }
}
