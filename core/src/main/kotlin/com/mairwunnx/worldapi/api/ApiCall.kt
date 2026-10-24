package com.mairwunnx.worldapi.api

import com.mairwunnx.worldapi.core.WorldApi
import io.ktor.server.application.ApplicationCall

/** What a handler gets: the request, the caller, and the services behind the API, typed as its platform has them. */
class ApiCall<out A : WorldApi>(val call: ApplicationCall, val endpoint: Endpoint<*>, val api: A, val principal: Principal) {
    val config get() = api.config

    /** Shown in the audit log; handlers set it to what they changed, such as a player name. */
    var auditTarget: String? = null
    var auditDetail: String? = null

    val seesCoordinates get() = principal.has(Scope.PLAYERS_PRIVATE) || (principal.anonymous && config.privacy.anonymous.coordinates)
    val seesInventories get() = principal.has(Scope.PLAYERS_PRIVATE) || (principal.anonymous && config.privacy.anonymous.inventories)
    val seesAddresses get() = principal.has(Scope.PLAYERS_MODERATE) || (principal.anonymous && config.privacy.anonymous.addresses)
    val seesChat get() = principal.has(Scope.CHAT_READ) || (principal.anonymous && config.privacy.anonymous.chat)
    /** Vanished players show only to moderators; to everyone else they look offline. */
    val seesVanished get() = principal.has(Scope.PLAYERS_MODERATE)

    // A tail parameter like {key...} arrives as several segments: rejoin them.
    fun path(name: String): String = call.parameters.getAll(name)?.joinToString("/")?.takeIf { it.isNotEmpty() } ?: badRequest("Missing path parameter $name")

    fun query(name: String): String? {
        // Reading a parameter the endpoint doesn't declare would leave it out of the OpenAPI document.
        check(endpoint.query.any { it.name == name }) { "Query parameter $name isn't declared on ${endpoint.path}" }
        return call.request.queryParameters[name]?.takeIf { it.isNotEmpty() }
    }

    fun int(name: String): Int? = query(name)?.let { it.toIntOrNull() ?: badRequest("$name must be an integer") }
    fun long(name: String): Long? = query(name)?.let { it.toLongOrNull() ?: badRequest("$name must be an integer") }
    fun double(name: String): Double? = query(name)?.let { it.toDoubleOrNull()?.takeIf(Double::isFinite) ?: badRequest("$name must be a number") }

    fun bool(name: String): Boolean? = query(name)?.let {
        when (it.lowercase()) {
            "true", "1" -> true
            "false", "0" -> false
            else -> badRequest("$name must be true or false")
        }
    }

    fun requireDouble(name: String) = double(name) ?: badRequest("$name is required")
    fun requireInt(name: String) = int(name) ?: badRequest("$name is required")

    /** 0 asks a paged list for its `total` alone. */
    val limit: Int get() {
        val limit = int("limit") ?: minOf(100, config.limits.maxPageSize)
        if (limit < 0) badRequest("limit can't be negative")
        return minOf(limit, config.limits.maxPageSize)
    }

    val cursor: String? get() = query("cursor")

    suspend fun <T> main(block: () -> T): T = api.main.call(block)

    fun require(scope: Scope) {
        if (!principal.has(scope)) throw ApiException(io.ktor.http.HttpStatusCode.Forbidden, "missing_scope", "This needs the ${scope.id} scope")
    }
}
