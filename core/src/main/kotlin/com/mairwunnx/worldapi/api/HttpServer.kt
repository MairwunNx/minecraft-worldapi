package com.mairwunnx.worldapi.api

import com.mairwunnx.worldapi.admin.Panel
import com.mairwunnx.worldapi.core.WorldApi
import com.mairwunnx.worldapi.store.AuditEntry
import com.mairwunnx.worldapi.ws.WebSocketHub
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receiveChannel
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.time.Duration.Companion.seconds

/** Embedded Ktor server: every endpoint, the OpenAPI document, the docs page, and the WebSocket. */
class HttpServer<A : WorldApi>(
    private val api: A,
    private val endpoints: List<Endpoint<A>>,
    private val hub: WebSocketHub,
    private val logger: Logger,
) {
    val limiter = RateLimiter()
    val exposure = Exposure(logger)

    // Settings the Ktor engine reads once; a change to these needs a restart (see changesNeedRestart).
    private val config = api.config

    private class AuthCache(val config: com.mairwunnx.worldapi.config.Auth, val generation: Int, val authenticator: Authenticator)

    @Volatile
    private var authCache: AuthCache? = null

    /** Tokens and anonymous access follow /worldapi reload and new in-game tokens without a restart. */
    private val auth: Authenticator
        get() {
            val current = api.config.auth
            val generation = api.tokens.generation
            authCache?.takeIf { it.config === current && it.generation == generation }?.let { return it.authenticator }
            return Authenticator(current, api.tokens.tokens).also { authCache = AuthCache(current, generation, it) }
        }
    private val base = config.http.basePath
    private var engine: EmbeddedServer<*, *>? = null

    /** Whether the server listens on a loopback address only; then it answers only requests that name the loopback. */
    @Volatile
    private var loopback = false

    /** Listens on [host] and [port], config.yml's unless the platform picks them, as in singleplayer. */
    fun start(host: String = config.http.host, port: Int = config.http.port) {
        loopback = Exposure.loopbackHost(host)
        engine = embeddedServer(CIO, host = host, port = port) { configure(this) }.also { it.start(wait = false) }
    }

    fun stop() {
        engine?.stop(gracePeriodMillis = 500, timeoutMillis = 2_000)
        engine = null
    }

    /** Installs everything on an application; tests call it on a test host. */
    fun configure(application: Application) = application.module()

    private fun Application.module() {
        install(WebSockets) {
            pingPeriod = config.websocket.heartbeatSeconds.seconds
            timeout = (config.websocket.heartbeatSeconds * 3).seconds
            maxFrameSize = 64 * 1024
        }
        if (config.cors.allowedOrigins.isNotEmpty()) {
            install(CORS) {
                listOf(HttpMethod.Get, HttpMethod.Post, HttpMethod.Put, HttpMethod.Patch, HttpMethod.Delete).forEach(::allowMethod)
                allowHeader(HttpHeaders.Authorization)
                allowHeader(HttpHeaders.ContentType)
                exposeHeader(HttpHeaders.RetryAfter)
                if ("*" in config.cors.allowedOrigins) anyHost()
                config.cors.allowedOrigins.filter { it != "*" }.forEach { origin ->
                    val (scheme, host) = origin.split("://", limit = 2).let { if (it.size == 2) it[0] to it[1] else "https" to it[0] }
                    allowHost(host, schemes = listOf(scheme))
                }
            }
        }
        install(StatusPages) {
            exception<ApiException> { call, error -> call.error(error.status, error.code, error.message) }
            exception<SerializationException> { call, error -> call.error(HttpStatusCode.BadRequest, "invalid_body", error.message ?: "Invalid JSON") }
            // Bukkit and the game reject bad arguments with IllegalArgumentException; its message tells the caller what was wrong.
            exception<IllegalArgumentException> { call, error -> call.error(HttpStatusCode.BadRequest, "invalid_request", error.message ?: "Invalid request") }
            exception<Throwable> { call, error ->
                logger.log(Level.WARNING, "${call.request.httpMethod.value} ${call.request.path()} failed", error)
                call.error(HttpStatusCode.InternalServerError, "internal_error", "Something went wrong; the server log has details")
            }
            status(HttpStatusCode.NotFound) { call, _ -> call.error(HttpStatusCode.NotFound, "not_found", "No such endpoint") }
            status(HttpStatusCode.MethodNotAllowed) { call, _ -> call.error(HttpStatusCode.MethodNotAllowed, "method_not_allowed", "This path doesn't take ${call.request.httpMethod.value}") }
        }
        // DNS rebinding: a page on another site can point its own name at 127.0.0.1, so a server that only listens there
        // trusts only requests that name the loopback, and browsers' requests from pages served elsewhere.
        intercept(io.ktor.server.application.ApplicationCallPipeline.Plugins) {
            if (loopback && !Exposure.loopbackRequest(context, config.cors.allowedOrigins)) {
                context.error(HttpStatusCode.Forbidden, "host_not_allowed", "This server answers only requests to localhost")
                finish()
            }
        }
        routing {
            route(base) {
                endpoints.forEach { install(it) }
                get("/openapi.json") {
                    guard(call, read = true)
                    call.respondText(openApiText(Panel.prefix(call) + base), ContentType.Application.Json)
                }
                get("/docs") {
                    guard(call, read = true)
                    // The page shares an origin with the panel, so its one outside script is pinned to a hash.
                    call.response.header("Content-Security-Policy", DOCS_CSP)
                    call.response.header("X-Content-Type-Options", "nosniff")
                    call.response.header("Referrer-Policy", "same-origin")
                    call.respondText(DOCS_PAGE, ContentType.Text.Html)
                }
                webSocket("/ws") {
                    val principal = guard(call, read = true, allowMissingToken = true)
                    hub.serve(this, principal)
                }
                Panel.authRoutes(this, this@HttpServer, api)
            }
            Panel.pages(this, base)
        }
    }

    private val openApiCache = mutableMapOf<String, String>()

    private fun openApiText(serverUrl: String) = synchronized(openApiCache) {
        // A client picks the prefix; a few are plenty, and more would only fill memory.
        if (serverUrl !in openApiCache && openApiCache.size >= 8) openApiCache.clear()
        openApiCache.getOrPut(serverUrl) {
            ApiJson.encodeToString(JsonObject.serializer(), document(endpoints, api.version, serverUrl))
        }
    }

    private fun Route.install(endpoint: Endpoint<A>) {
        route(endpoint.path, endpoint.method) {
            handle {
                val principal = guard(call, read = !endpoint.writes, scope = endpoint.scope, orScopes = endpoint.orScopes)
                val apiCall = ApiCall(call, endpoint, api, principal)
                var status = HttpStatusCode.InternalServerError
                try {
                    val body = endpoint.body?.let { decode(it, receiveLimited(call)) }
                    val result = apiCall.(endpoint.handler)(body)
                    @Suppress("UNCHECKED_CAST")
                    val response = endpoint.response as KSerializer<Any?>?
                    status = if (response == null) HttpStatusCode.NoContent else endpoint.status
                    if (response == null) call.respond(HttpStatusCode.NoContent)
                    else call.respondText(ApiJson.encodeToString(response, result), ContentType.Application.Json, endpoint.status)
                } catch (error: ApiException) {
                    status = error.status
                    throw error
                } catch (error: SerializationException) {
                    status = HttpStatusCode.BadRequest
                    throw error
                } catch (error: IllegalArgumentException) {
                    status = HttpStatusCode.BadRequest
                    throw error
                } finally {
                    if (endpoint.writes) audit(apiCall, status)
                }
            }
        }
    }

    /**
     * Reads the body, refusing anything past http.maxBodyBytes, whether or not the client
     * declared its length, so a huge upload can't exhaust memory.
     */
    private suspend fun receiveLimited(call: ApplicationCall): String {
        val limit = api.config.http.maxBodyBytes
        val tooLarge = ApiException(HttpStatusCode.PayloadTooLarge, "body_too_large", "The body is larger than $limit bytes")
        call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()?.let { if (it > limit) throw tooLarge }
        val bytes = call.receiveChannel().readRemaining(limit + 1).readByteArray()
        if (bytes.size > limit) throw tooLarge
        return bytes.decodeToString()
    }

    private fun decode(serializer: KSerializer<*>, text: String): Any? =
        ApiJson.decodeFromString(serializer, text.ifBlank { "{}" })

    /**
     * Authenticates, checks the scope, and takes a rate-limit token. Anonymous clients may only
     * read the paths in auth.anonymous.endpoints. A WebSocket client may also send its token in
     * the first message, so a missing token isn't fatal there.
     */
    suspend fun guard(call: ApplicationCall, read: Boolean, scope: Scope = Scope.SERVER_READ, allowMissingToken: Boolean = false, orScopes: List<Scope> = emptyList()): Principal {
        exposure.check(call, api.config.http.trustedProxies)
        val header = call.request.headers[HttpHeaders.Authorization]
        val token = Authenticator.bearer(header)
        val session = call.request.cookies[Panel.COOKIE]?.takeIf { header == null }
        val principal = when {
            token != null -> auth.authenticate(token) ?: throw ApiException(HttpStatusCode.Unauthorized, "invalid_token", "The token isn't valid")
            header != null -> throw ApiException(HttpStatusCode.Unauthorized, "invalid_token", "Send the token as Authorization: Bearer <token>")
            session != null -> {
                // A cookie rides along with any request the browser makes; a custom header proves the panel sent it,
                // and a WebSocket from another site's page shows itself by its Origin.
                if (!Panel.sameOrigin(call)) throw ApiException(HttpStatusCode.Forbidden, "csrf", "The panel's session works from its own site only")
                if (!read && call.request.headers[Panel.CSRF_HEADER] != "1") throw ApiException(HttpStatusCode.Forbidden, "csrf", "Send ${Panel.CSRF_HEADER}: 1 with the panel's writes")
                val account = api.accounts.session(session) ?: throw ApiException(HttpStatusCode.Unauthorized, "session_expired", "Sign in again")
                Panel.principal(account, api.config.auth)
            }
            read && auth.anonymousAllowed(call.request.path().removePrefix(base)) -> Principal.anonymous(clientAddress(call))
            allowMissingToken -> Principal.anonymous(clientAddress(call)).copy(scopes = emptySet())
            else -> throw ApiException(HttpStatusCode.Unauthorized, "token_required", "This endpoint needs a token")
        }
        // Only a WebSocket waiting for its first message passes without permissions; a token or account with none gets nothing.
        val waitsForToken = allowMissingToken && principal.anonymous && principal.scopes.isEmpty()
        if (!waitsForToken && !principal.has(scope) && orScopes.none(principal::has)) {
            throw ApiException(HttpStatusCode.Forbidden, "missing_scope", "The token \"${principal.name}\" lacks the ${scope.id} scope")
        }
        val limits = api.config.rateLimit
        val limit = auth.rateLimitOf(principal) ?: if (principal.anonymous) limits.anonymous else limits.tokens
        val wait = limiter.acquire(principal.key, limit)
        if (wait > 0) {
            call.response.header(HttpHeaders.RetryAfter, wait.toString())
            throw ApiException(HttpStatusCode.TooManyRequests, "rate_limited", "Too many requests; retry in $wait s")
        }
        return principal
    }

    fun authenticate(token: String) = auth.authenticate(token)
    fun anonymousAllowed(path: String) = auth.anonymousAllowed(path)

    /**
     * The client's address for rate limits. X-Forwarded-For counts only when the direct peer is
     * a trusted proxy; then the rightmost address that isn't a trusted proxy wins, because
     * everything left of it could be made up by the client.
     */
    fun clientAddress(call: ApplicationCall): String {
        val peer = call.request.origin.remoteAddress
        val settings = api.config.http
        if (!settings.trustForwardedFor) return peer
        val proxies = settings.trustedProxies.mapNotNull(Cidr::parse)
        fun trusted(address: String) = proxies.any { it.contains(address) }
        if (!trusted(peer)) return peer
        val chain = call.request.headers.getAll("X-Forwarded-For").orEmpty().flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }
        return chain.lastOrNull { !trusted(it) } ?: chain.firstOrNull() ?: peer
    }

    private fun audit(call: ApiCall<A>, status: HttpStatusCode) {
        val target = call.auditTarget ?: call.call.request.path().removePrefix(base)
        val method = call.endpoint.method.value
        logger.info("audit: ${call.principal.name} $method ${call.endpoint.path} target=$target -> ${status.value}")
        api.history.audit(AuditEntry(call.principal.name, method, call.call.request.path(), target, status.value, call.auditDetail))
    }

    companion object {
        /** Whether a new config touches settings the running engine can't pick up. */
        fun changesNeedRestart(old: com.mairwunnx.worldapi.config.WorldApiConfig, new: com.mairwunnx.worldapi.config.WorldApiConfig) =
            old.http.copy(trustForwardedFor = false) != new.http.copy(trustForwardedFor = false) ||
                old.cors != new.cors || old.websocket.heartbeatSeconds != new.websocket.heartbeatSeconds

        fun document(endpoints: List<Endpoint<*>>, version: String, serverUrl: String): JsonObject =
            OpenApi("WorldAPI", version).document(endpoints, serverUrl, mapOf("/ws" to WebSocketHub.openApiPath()))

        // A new version needs its hash: curl -sL <the script's URL> | openssl dgst -sha384 -binary | openssl base64 -A
        private const val SCALAR_VERSION = "1.73.1"
        private const val SCALAR_INTEGRITY = "sha384-kYDGzV91Jnn3TbHINV3nt54riK2uMJDfN5Al8dAkz4FssELTBWbD8rgw32sTKfOi"

        private const val DOCS_CSP = "default-src 'self'; script-src https://cdn.jsdelivr.net; style-src 'self' 'unsafe-inline' https:; " +
            "font-src 'self' data: https:; img-src 'self' data: https:; connect-src 'self'; worker-src 'self' blob:; " +
            "frame-ancestors 'none'; base-uri 'none'; form-action 'none'"

        private val DOCS_PAGE = """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>WorldAPI</title>
            </head>
            <body>
              <script id="api-reference" data-url="openapi.json"></script>
              <script src="https://cdn.jsdelivr.net/npm/@scalar/api-reference@$SCALAR_VERSION/dist/browser/standalone.js"
                integrity="$SCALAR_INTEGRITY" crossorigin="anonymous"></script>
            </body>
            </html>
        """.trimIndent()
    }
}

private suspend fun ApplicationCall.error(status: HttpStatusCode, code: String, message: String) {
    respondText(ApiJson.encodeToString(ErrorBody.serializer(), ErrorBody(ErrorDetail(code, message))), ContentType.Application.Json, status)
}
