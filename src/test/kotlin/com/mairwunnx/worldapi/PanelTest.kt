package com.mairwunnx.worldapi

import com.mairwunnx.worldapi.admin.AdminAccounts
import com.mairwunnx.worldapi.admin.Panel
import com.mairwunnx.worldapi.store.AdminSessions
import org.jetbrains.exposed.v1.jdbc.select
import com.mairwunnx.worldapi.admin.Passwords
import com.mairwunnx.worldapi.api.HttpServer
import com.mairwunnx.worldapi.config.Anonymous
import com.mairwunnx.worldapi.config.Auth
import com.mairwunnx.worldapi.config.WorldApiConfig
import com.mairwunnx.worldapi.routes.manageRoutes
import com.mairwunnx.worldapi.ws.WebSocketHub
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PanelTest {
    private val config = WorldApiConfig(auth = Auth(anonymous = Anonymous(endpoints = emptyList())))

    private fun test(block: suspend ApplicationTestBuilder.(TestApi) -> Unit) = TestApi(config).use { fake ->
        val hub = WebSocketHub(fake.ref::get, { null }) { _, _ -> emptyList() }
        testApplication {
            application { HttpServer(fake.api, manageRoutes, hub, Logger.getAnonymousLogger()).configure(this) }
            block(fake)
        }
    }

    private fun code(body: String) = Json.parseToJsonElement(body).jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.content

    private fun HttpResponse.session(): String =
        headers.getAll(HttpHeaders.SetCookie).orEmpty().first { it.startsWith(Panel.COOKIE + "=") }.substringAfter('=').substringBefore(';')

    private fun HttpRequestBuilder.json(body: String) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private fun HttpRequestBuilder.signedIn(session: String, csrf: Boolean = true) {
        header(HttpHeaders.Cookie, "${Panel.COOKIE}=$session")
        if (csrf) header(Panel.CSRF_HEADER, "1")
    }

    @Test
    fun `passwords hash and verify`() {
        val hash = Passwords.hash("correct horse", iterations = 1_000)
        assertTrue(Passwords.verify("correct horse", hash))
        assertFalse(Passwords.verify("correct hors", hash))
        assertFalse(Passwords.verify("x", "nonsense"))
    }

    @Test
    fun `a code from the game makes an account that signs in with a cookie`() = test { fake ->
        assertContains(client.get("/api/v1/auth/status").bodyAsText(), "\"accounts\":false")
        val code = fake.api.accounts.issueCode("Steve", null, listOf("owner"), "Steve")
        assertEquals("weak_password", code(client.post("/api/v1/auth/setup") { json("""{"code":"$code","password":"short"}""") }.bodyAsText()))
        val setup = client.post("/api/v1/auth/setup") { json("""{"code":"${code.lowercase().replace("-", "")}","password":"a long password"}""") }
        assertEquals(HttpStatusCode.OK, setup.status, setup.bodyAsText())
        val cookie = setup.headers.getAll(HttpHeaders.SetCookie)!!.first { it.startsWith(Panel.COOKIE) }
        assertContains(cookie, "HttpOnly")
        assertContains(cookie, "SameSite=Strict")
        val session = setup.session()
        // The database keeps a digest, never the cookie.
        val digests = fake.store.read { AdminSessions.select(AdminSessions.digest).map { it[AdminSessions.digest] } }
        assertEquals(listOf(AdminAccounts.digest(session)), digests)

        val me = client.get("/api/v1/auth/me") { signedIn(session) }.bodyAsText()
        assertContains(me, "\"kind\":\"account\"")
        assertContains(me, "files:write")
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/tokens") { signedIn(session) }.status)

        // A used code is gone.
        assertEquals("invalid_code", code(client.post("/api/v1/auth/setup") { json("""{"code":"$code","password":"a long password"}""") }.bodyAsText()))
    }

    @Test
    fun `cookie writes need the panel header and the same origin`() = test { fake ->
        val session = signUp(fake, "Alex", "owner")
        val body = """{"name":"agent","scopes":["viewer"]}"""
        val noHeader = client.post("/api/v1/tokens") { signedIn(session, csrf = false); json(body) }
        assertEquals(HttpStatusCode.Forbidden, noHeader.status)
        assertEquals("csrf", code(noHeader.bodyAsText()))
        val elsewhere = client.post("/api/v1/tokens") { signedIn(session); header(HttpHeaders.Origin, "https://evil.example"); json(body) }
        assertEquals("csrf", code(elsewhere.bodyAsText()))
        val created = client.post("/api/v1/tokens") { signedIn(session); json(body) }
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        assertEquals(64, Json.parseToJsonElement(created.bodyAsText()).jsonObject["token"]!!.jsonPrimitive.content.length)
    }

    @Test
    fun `nobody hands out permissions they lack`() = test { fake ->
        val admin = signUp(fake, "Helper", "admin")
        val owner = client.post("/api/v1/accounts/invite") { signedIn(admin); json("""{"groups":["owner"]}""") }
        assertEquals(HttpStatusCode.Forbidden, owner.status)
        assertEquals("cannot_grant", code(owner.bodyAsText()))
        assertEquals("cannot_grant", code(client.post("/api/v1/tokens") { signedIn(admin); json("""{"name":"x","scopes":["files:write"]}""") }.bodyAsText()))
        assertEquals(HttpStatusCode.Created, client.post("/api/v1/accounts/invite") { signedIn(admin); json("""{"groups":["moderator"]}""") }.status)
        assertEquals(HttpStatusCode.BadRequest, client.post("/api/v1/tokens") { signedIn(admin); json("""{"name":"y","scopes":["wizard"]}""") }.status)
    }

    @Test
    fun `sign-in checks the password and logout ends the session`() = test { fake ->
        signUp(fake, "Steve", "viewer")
        assertEquals("invalid_login", code(client.post("/api/v1/auth/login") { json("""{"name":"Steve","password":"wrong password"}""") }.bodyAsText()))
        assertEquals("invalid_login", code(client.post("/api/v1/auth/login") { json("""{"name":"Nobody","password":"wrong password"}""") }.bodyAsText()))
        val login = client.post("/api/v1/auth/login") { json("""{"name":"Steve","password":"a long password"}""") }
        assertEquals(HttpStatusCode.OK, login.status)
        val session = login.session()
        // viewer lacks tokens:manage.
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/tokens") { signedIn(session) }.status)
        client.post("/api/v1/auth/logout") { signedIn(session) }
        assertEquals("session_expired", code(client.get("/api/v1/auth/me") { signedIn(session) }.bodyAsText()))
    }

    @Test
    fun `a reset code sets a new password`() = test { fake ->
        signUp(fake, "Steve", "viewer")
        val reset = fake.api.accounts.issueCode("Steve", null, listOf("viewer"), "Steve", reset = true)
        assertEquals(HttpStatusCode.OK, client.post("/api/v1/auth/setup") { json("""{"code":"$reset","password":"another long one"}""") }.status)
        assertEquals(HttpStatusCode.OK, client.post("/api/v1/auth/login") { json("""{"name":"Steve","password":"another long one"}""") }.status)
    }

    @Test
    fun `the panel page is missing without a build, and assets stay assets`() = test {
        val page = client.get("/admin/")
        assertNotNull(page.headers["Content-Security-Policy"])
        assertTrue(page.status == HttpStatusCode.OK || page.status == HttpStatusCode.NotFound)
        assertEquals(HttpStatusCode.NotFound, client.get("/admin/assets/nope.js").status)
    }

    private suspend fun ApplicationTestBuilder.signUp(fake: TestApi, name: String, group: String): String {
        val code = fake.api.accounts.issueCode(name, null, listOf(group), "test")
        val response = client.post("/api/v1/auth/setup") { json("""{"code":"$code","password":"a long password"}""") }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return response.session()
    }
}

class ExposureTest {
    @Test
    fun `the warning frame lines up`() {
        val frame = com.mairwunnx.worldapi.api.Exposure.frame("straight to port 8110 over plain HTTP").lines()
        assertEquals(1, frame.map { it.length }.distinct().size)
        assertTrue(frame.first().startsWith("+-") && frame.last().endsWith("-+"))
        assertTrue(com.mairwunnx.worldapi.api.Exposure.local("192.168.1.5"))
        assertTrue(com.mairwunnx.worldapi.api.Exposure.local("172.18.0.3"))
        assertFalse(com.mairwunnx.worldapi.api.Exposure.local("8.8.8.8"))
    }
}
