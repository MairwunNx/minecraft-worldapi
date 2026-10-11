package com.mairwunnx.worldapi.api

import com.mairwunnx.worldapi.config.Auth
import com.mairwunnx.worldapi.config.RateLimit
import java.security.MessageDigest

/**
 * Resolves a bearer token to a principal. Tokens are compared as SHA-256 digests with
 * [MessageDigest.isEqual], and every configured token is checked, so the time a lookup takes
 * says nothing about which token came close.
 */
class Authenticator(config: Auth) {
    private class Entry(val digest: ByteArray, val principal: Principal, val rateLimit: RateLimit?)

    private val entries = config.tokens.map {
        Entry(sha256(it.token), Principal(it.name, it.scopes.toSet(), "token:${it.name}"), it.rateLimit)
    }
    private val anonymous = config.anonymous
    private val anonymousPatterns = anonymous.endpoints.map(::PathPattern)

    /** The principal for a token, or null when no configured token matches. */
    fun authenticate(token: String): Principal? {
        val digest = sha256(token)
        var found: Entry? = null
        for (entry in entries) {
            if (MessageDigest.isEqual(entry.digest, digest)) found = entry
        }
        return found?.principal
    }

    fun rateLimitOf(principal: Principal): RateLimit? = entries.firstOrNull { it.principal == principal }?.rateLimit

    /** Whether a client without a token may GET this path, relative to the base path. */
    fun anonymousAllowed(path: String) = anonymous.enabled && anonymousPatterns.any { it.matches(path) }

    companion object {
        /** Pulls the token out of an `Authorization: Bearer …` header. */
        fun bearer(header: String?): String? {
            if (header == null) return null
            val (kind, value) = header.trim().split(' ', limit = 2).takeIf { it.size == 2 } ?: return null
            return value.trim().takeIf { kind.equals("Bearer", ignoreCase = true) && it.isNotEmpty() }
        }

        private fun sha256(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
    }
}

/** A path glob: `*` matches one segment, `**` any number of segments, anything else matches itself. */
class PathPattern(pattern: String) {
    private val parts = split(pattern)

    fun matches(path: String) = match(parts, split(path))

    private fun match(pattern: List<String>, path: List<String>): Boolean = when {
        pattern.isEmpty() -> path.isEmpty()
        pattern[0] == "**" -> (0..path.size).any { match(pattern.drop(1), path.drop(it)) }
        path.isEmpty() -> false
        pattern[0] == "*" || pattern[0] == path[0] -> match(pattern.drop(1), path.drop(1))
        else -> false
    }

    private fun split(path: String) = path.trim('/').split('/').filter { it.isNotEmpty() }
}
