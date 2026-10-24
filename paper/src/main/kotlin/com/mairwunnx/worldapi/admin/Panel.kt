package com.mairwunnx.worldapi.admin

import com.mairwunnx.worldapi.api.ApiException
import com.mairwunnx.worldapi.api.ApiJson
import com.mairwunnx.worldapi.api.HttpServer
import com.mairwunnx.worldapi.api.Principal
import com.mairwunnx.worldapi.api.Scope
import com.mairwunnx.worldapi.config.Auth
import com.mairwunnx.worldapi.config.RateLimit
import com.mairwunnx.worldapi.core.WorldApi
import com.mairwunnx.worldapi.store.AuditEntry
import io.ktor.http.ContentType
import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.fromFilePath
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.request.receiveChannel
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import java.net.URI

@Serializable
data class LoginRequest(val name: String, val password: String)

@Serializable
data class SetupRequest(
    /** The code from /worldapi admin in game, or from an invite. */
    val code: String,
    /** Needed when the code came from an invite; a code from the game names the account after the player. */
    val name: String? = null,
    val password: String,
)

@Serializable
data class PasswordChange(val current: String, val password: String)

@Serializable
data class Me(
    val name: String,
    /** `account` for the panel, `token` for a bearer token, `anonymous` without either. */
    val kind: String,
    val scopes: List<String>,
    val groups: List<String> = emptyList(),
    val player: String? = null,
)

@Serializable
data class PanelStatus(
    /** Whether any account exists; without one, the first comes from /worldapi admin in game. */
    val accounts: Boolean,
    val version: String,
    /** False when this request came from the internet without HTTPS; the panel then warns before anyone types a password. */
    val secure: Boolean = true,
    /** How it came, when not secure. */
    val insecureReason: String? = null,
)

/**
 * The admin panel: a React app built into the jar under /admin, and the sign-in endpoints under
 * the base path. The panel then calls the same REST API as everyone, with a session cookie
 * instead of a token.
 */
object Panel {
    const val COOKIE = "worldapi_session"
    const val CSRF_HEADER = "X-WorldAPI-Panel"
    private val signInLimit = RateLimit(requestsPerMinute = 10, burst = 5)

    fun principal(account: AdminAccounts.Account, auth: Auth) =
        Principal("panel:${account.name}", auth.resolver.resolve(account.groups), "account:${account.name}")

    /** True without an Origin header (same-site navigation and non-browser clients) or when it names this host. */
    fun sameOrigin(call: ApplicationCall): Boolean {
        val origin = call.request.header(HttpHeaders.Origin) ?: return true
        val host = call.request.header("X-Forwarded-Host") ?: call.request.header(HttpHeaders.Host) ?: return false
        return runCatching { URI(origin).let { it.host + (if (it.port > 0) ":${it.port}" else "") } }.getOrNull()
            ?.let { it.equals(host, ignoreCase = true) || it.substringBefore(':').equals(host.substringBefore(':'), ignoreCase = true) } == true
    }

    fun authRoutes(route: Route, http: HttpServer, api: WorldApi) = with(route) {
        get("/auth/status") {
            http.exposure.check(call, api.config.http.trustedProxies)
            val reason = http.exposure.insecure(call, api.config.http.trustedProxies)
            call.json(PanelStatus.serializer(), PanelStatus(api.accounts.count() > 0, api.version, reason == null, reason))
        }

        get("/auth/me") {
            val principal = http.guard(call, read = true, allowMissingToken = true)
            val account = principal.name.removePrefix("panel:").takeIf { principal.name.startsWith("panel:") }?.let { api.accounts.find(it) }
            val kind = when {
                account != null -> "account"
                principal.anonymous -> "anonymous"
                else -> "token"
            }
            call.json(Me.serializer(), Me(account?.name ?: principal.name, kind, principal.scopes.sortedBy { it.ordinal }.map { it.id }, account?.groups.orEmpty(),
                account?.playerUuid?.let { uuid -> api.main.call { api.server.getOfflinePlayer(java.util.UUID.fromString(uuid)).name } }))
        }

        post("/auth/login") {
            limit(http, call)
            val request = call.body(LoginRequest.serializer())
            val account = withContext(Dispatchers.Default) { api.accounts.verify(request.name.trim(), request.password) }
            if (account == null) {
                // Anyone on the internet picks this name, and agents read the audit log: keep it only when it could be an account.
                val tried = request.name.trim().takeIf(AdminAccounts.NAME::matches) ?: "?"
                api.history.audit(AuditEntry("panel:$tried", "POST", call.request.local.uri, "sign-in", 401, "wrong name or password"))
                throw ApiException(HttpStatusCode.Unauthorized, "invalid_login", "Wrong name or password")
            }
            signIn(call, api, account)
        }

        post("/auth/setup") {
            limit(http, call)
            val request = call.body(SetupRequest.serializer())
            checkPassword(request.password)
            val code = api.accounts.takeCode(request.code) ?: throw ApiException(HttpStatusCode.Unauthorized, "invalid_code", "The code is wrong, used, or older than 30 minutes; get a new one with /worldapi admin")
            val name = (code.name ?: request.name?.trim()) ?: throw ApiException(HttpStatusCode.BadRequest, "name_required", "Pick a name")
            if (!AdminAccounts.NAME.matches(name)) throw ApiException(HttpStatusCode.BadRequest, "invalid_name", "Names are 3–32 letters, digits, dots, dashes, or underscores")
            if (code.reset) {
                val account = api.accounts.find(name) ?: throw ApiException(HttpStatusCode.NotFound, "account_not_found", "The account $name is gone")
                withContext(Dispatchers.Default) { api.accounts.setPassword(name, request.password) }
                api.history.audit(AuditEntry("panel:$name", "POST", call.request.local.uri, "account $name", 200, "new password from a code by ${code.createdBy}"))
                return@post signIn(call, api, account)
            }
            if (api.accounts.find(name) != null) throw ApiException(HttpStatusCode.Conflict, "account_exists", "An account named $name exists; sign in instead")
            val account = withContext(Dispatchers.Default) { api.accounts.create(name, request.password, code.groups, code.playerUuid, code.createdBy) }
            api.history.audit(AuditEntry("panel:$name", "POST", call.request.local.uri, "account $name", 200, "created from a code by ${code.createdBy} with ${code.groups.joinToString()}"))
            signIn(call, api, account)
        }

        post("/auth/password") {
            limit(http, call)
            val principal = http.guard(call, read = false)
            val name = principal.name.removePrefix("panel:").takeIf { principal.name.startsWith("panel:") }
                ?: throw ApiException(HttpStatusCode.BadRequest, "not_an_account", "Only panel accounts have passwords")
            val request = call.body(PasswordChange.serializer())
            checkPassword(request.password)
            withContext(Dispatchers.Default) { api.accounts.verify(name, request.current) } ?: throw ApiException(HttpStatusCode.Unauthorized, "invalid_login", "The current password is wrong")
            withContext(Dispatchers.Default) { api.accounts.setPassword(name, request.password) }
            api.history.audit(AuditEntry(principal.name, "POST", call.request.local.uri, "account $name", 200, "changed the password"))
            // Changing the password ended every session, this one too: start a fresh one.
            signIn(call, api, api.accounts.find(name)!!)
        }

        get("/icons/{key}") {
            http.guard(call, read = true, scope = Scope.SERVER_READ)
            val icons = api.icons?.takeIf { api.config.panel.icons }
                ?: throw ApiException(HttpStatusCode.NotFound, "icons_off", "Item icons are off: panel.icons in config.yml")
            val key = call.parameters["key"].orEmpty()
            val icon = withContext(Dispatchers.IO) { icons.icon(key) }
            when {
                icon != null -> {
                    call.response.header(HttpHeaders.CacheControl, "public, max-age=86400")
                    call.respondBytes(icon, ContentType.Image.PNG)
                }
                icons.status == Icons.State.LOADING || icons.status == Icons.State.IDLE -> {
                    call.response.header(HttpHeaders.RetryAfter, "5")
                    throw ApiException(HttpStatusCode.ServiceUnavailable, "icons_loading", "WorldAPI is fetching the game's textures; try again shortly")
                }
                icons.status == Icons.State.FAILED -> throw ApiException(HttpStatusCode.ServiceUnavailable, "icons_unavailable", "WorldAPI couldn't fetch the game's textures; the server log says why")
                else -> throw ApiException(HttpStatusCode.NotFound, "icon_not_found", "No icon for $key")
            }
        }

        post("/auth/logout") {
            call.request.cookies[COOKIE]?.let(api.accounts::endSession)
            call.response.cookies.append(cookie(call, "", maxAge = 0))
            call.respondText("{}", ContentType.Application.Json)
        }
    }

    /** Serves the built panel: files as they are, any other path the app's index with its base filled in. */
    fun pages(routing: Routing, base: String) = with(routing) {
        get("/admin") { call.respondRedirect("admin/") }
        get("/admin/{path...}") {
            val path = call.parameters.getAll("path").orEmpty().joinToString("/")
            call.response.header("Content-Security-Policy", CSP)
            call.response.header("X-Content-Type-Options", "nosniff")
            call.response.header("Referrer-Policy", "same-origin")
            val file = path.takeIf { it.isNotEmpty() && ".." !in it }?.let { resource("admin/$it") }
            if (file != null) {
                // Vite names assets by their hash, so they never change under one name.
                if (path.startsWith("assets/")) call.response.header(HttpHeaders.CacheControl, "public, max-age=31536000, immutable")
                call.respondBytes(file, ContentType.fromFilePath(path).firstOrNull() ?: ContentType.Application.OctetStream)
                return@get
            }
            if (path.startsWith("assets/")) {
                call.respondText("Not found", status = HttpStatusCode.NotFound)
                return@get
            }
            val index = resource("admin/index.html")?.decodeToString()
                ?: return@get call.respondText("The admin panel isn't in this build of WorldAPI", status = HttpStatusCode.NotFound)
            val prefix = prefix(call)
            val page = index.replace("<head>", "<head>\n    <base href=\"$prefix/admin/\">\n    <meta name=\"worldapi-api\" content=\"$prefix$base\">")
            call.response.header(HttpHeaders.CacheControl, "no-cache")
            call.respondText(page, ContentType.Text.Html)
        }
    }

    /** The path a proxy serves WorldAPI under, from X-Forwarded-Prefix; empty when it's missing or holds anything but a plain path. */
    fun prefix(call: ApplicationCall): String =
        call.request.header("X-Forwarded-Prefix")?.trimEnd('/').orEmpty().takeIf { it.isEmpty() || SAFE_PREFIX.matches(it) }.orEmpty()

    private suspend fun signIn(call: ApplicationCall, api: WorldApi, account: AdminAccounts.Account) {
        val token = api.accounts.startSession(account.name, call.request.local.remoteAddress, call.request.header(HttpHeaders.UserAgent))
        call.response.cookies.append(cookie(call, token, maxAge = (AdminAccounts.SESSION_MILLIS / 1000).toInt()))
        val principal = principal(account, api.config.auth)
        call.json(Me.serializer(), Me(account.name, "account", principal.scopes.sortedBy { it.ordinal }.map { it.id }, account.groups))
    }

    private fun cookie(call: ApplicationCall, value: String, maxAge: Int) = Cookie(
        name = COOKIE, value = value, encoding = CookieEncoding.RAW, maxAge = maxAge, path = "/",
        secure = call.request.header("X-Forwarded-Proto") == "https" || call.request.local.scheme == "https",
        httpOnly = true, extensions = mapOf("SameSite" to "Strict"),
    )

    private fun checkPassword(password: String) {
        if (password.length < Passwords.MIN_LENGTH) throw ApiException(HttpStatusCode.BadRequest, "weak_password", "Passwords need at least ${Passwords.MIN_LENGTH} characters")
        if (password.length > 256) throw ApiException(HttpStatusCode.BadRequest, "weak_password", "Passwords stop at 256 characters")
    }

    /** Sign-in attempts per address, far below the API's own limits: passwords are guessable, tokens aren't. */
    private fun limit(http: HttpServer, call: ApplicationCall) {
        val wait = http.limiter.acquire("signin:${http.clientAddress(call)}", signInLimit)
        if (wait > 0) {
            call.response.header(HttpHeaders.RetryAfter, wait.toString())
            throw ApiException(HttpStatusCode.TooManyRequests, "rate_limited", "Too many attempts; retry in $wait s")
        }
    }

    /** Reads at most 4 KiB, so a huge body is refused before it fills memory. */
    private suspend fun <T> ApplicationCall.body(serializer: KSerializer<T>): T {
        val bytes = receiveChannel().readRemaining(BODY_LIMIT + 1L).readByteArray()
        if (bytes.size > BODY_LIMIT) throw ApiException(HttpStatusCode.PayloadTooLarge, "body_too_large", "The body is too large")
        return ApiJson.decodeFromString(serializer, bytes.decodeToString().ifBlank { "{}" })
    }

    private const val BODY_LIMIT = 4_096

    private suspend fun <T> ApplicationCall.json(serializer: KSerializer<T>, value: T) =
        respondText(ApiJson.encodeToString(serializer, value), ContentType.Application.Json)

    private fun resource(path: String): ByteArray? = Panel::class.java.classLoader.getResourceAsStream(path)?.use { it.readBytes() }

    private val SAFE_PREFIX = Regex("/[A-Za-z0-9/_.-]*")

    // The panel loads nothing from elsewhere; inline styles come from the UI library.
    private const val CSP = "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; " +
        "connect-src 'self'; font-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'"
}

/** A principal hands out, in tokens and accounts, only permissions it has itself. */
internal fun Principal.canGrant(scopes: Set<Scope>) = scopes.all(::has)
