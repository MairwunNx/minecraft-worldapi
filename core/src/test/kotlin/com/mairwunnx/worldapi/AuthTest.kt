package com.mairwunnx.worldapi

import com.mairwunnx.worldapi.api.Authenticator
import com.mairwunnx.worldapi.api.PathPattern
import com.mairwunnx.worldapi.api.Principal
import com.mairwunnx.worldapi.api.RateLimiter
import com.mairwunnx.worldapi.api.Scope
import com.mairwunnx.worldapi.config.Anonymous
import com.mairwunnx.worldapi.config.Auth
import com.mairwunnx.worldapi.config.RateLimit
import com.mairwunnx.worldapi.config.Token
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuthTest {
    private val auth = Authenticator(
        Auth(
            tokens = listOf(
                Token("site", "site-token-0123456789", listOf("read")),
                Token("admin", "admin-token-0123456789", listOf("admin"), RateLimit(10, 5)),
            ),
            anonymous = Anonymous(endpoints = listOf("/server", "/players/*", "/leaderboards/**")),
        ),
    )

    @Test
    fun `tokens resolve to their principal`() {
        assertEquals("site", auth.authenticate("site-token-0123456789")?.name)
        assertEquals("admin", auth.authenticate("admin-token-0123456789")?.name)
        assertNull(auth.authenticate("site-token-012345678"))
        assertNull(auth.authenticate(""))
    }

    @Test
    fun `bearer headers parse`() {
        assertEquals("abc", Authenticator.bearer("Bearer abc"))
        assertEquals("abc", Authenticator.bearer("bearer   abc "))
        assertNull(Authenticator.bearer("Basic abc"))
        assertNull(Authenticator.bearer("Bearer"))
        assertNull(Authenticator.bearer(null))
    }

    @Test
    fun `admin includes every scope but code and files write`() {
        val admin = auth.authenticate("admin-token-0123456789")!!
        assertTrue(admin.has(Scope.SERVER_READ))
        assertTrue(admin.has(Scope.WORLD_WRITE))
        assertTrue(admin.has(Scope.FILES_READ))
        assertFalse(admin.has(Scope.FILES_WRITE))
        assertFalse(admin.has(Scope.CODE))
        // A 2.x read token keeps everything read gave it.
        val site = auth.authenticate("site-token-0123456789")!!
        assertEquals(setOf(Scope.SERVER_READ, Scope.PLAYERS_READ, Scope.PLAYERS_PRIVATE, Scope.EVENTS_READ, Scope.CHAT_READ), site.scopes)
        assertEquals(RateLimit(10, 5), auth.rateLimitOf(admin))
        assertNull(auth.rateLimitOf(site))
    }

    @Test
    fun `anonymous paths follow the globs`() {
        assertTrue(auth.anonymousAllowed("/server"))
        assertTrue(auth.anonymousAllowed("/server/"))
        assertFalse(auth.anonymousAllowed("/server/commands"))
        assertTrue(auth.anonymousAllowed("/players/Steve"))
        assertFalse(auth.anonymousAllowed("/players/Steve/inventory"))
        assertTrue(auth.anonymousAllowed("/leaderboards"))
        assertTrue(auth.anonymousAllowed("/leaderboards/a/b/c"))
        assertFalse(Authenticator(Auth(anonymous = Anonymous(enabled = false))).anonymousAllowed("/server"))
    }

    @Test
    fun `path patterns`() {
        assertTrue(PathPattern("/a/**/z").matches("/a/z"))
        assertTrue(PathPattern("/a/**/z").matches("/a/b/c/z"))
        assertFalse(PathPattern("/a/*").matches("/a"))
        assertFalse(PathPattern("/a").matches("/b"))
    }

    @Test
    fun `anonymous principals are keyed by address`() {
        val principal = Principal.anonymous("1.2.3.4")
        assertTrue(principal.anonymous)
        assertEquals("ip:1.2.3.4", principal.key)
        assertEquals(Scope.anonymous, principal.scopes)
    }

    @Test
    fun `rate limiter allows a burst, then refills`() {
        var now = 0L
        val limiter = RateLimiter { now }
        val limit = RateLimit(requestsPerMinute = 60, burst = 3)
        repeat(3) { assertEquals(0, limiter.acquire("k", limit)) }
        assertEquals(1, limiter.acquire("k", limit))
        assertEquals(0, limiter.acquire("other", limit))
        now += 1_000_000_000L
        assertEquals(0, limiter.acquire("k", limit))
        assertEquals(1, limiter.acquire("k", limit))
    }

    @Test
    fun `groups nest, and cycles and unknown names fail`() {
        val groups = com.mairwunnx.worldapi.api.Groups(mapOf("a" to listOf("server:read", "b"), "b" to listOf("code"), "loop" to listOf("loop2"), "loop2" to listOf("loop")))
        assertEquals(setOf(Scope.SERVER_READ, Scope.CODE), groups.resolve(listOf("a")))
        val unknown = mutableListOf<String>()
        groups.resolve(listOf("wizard"), unknown::add)
        assertEquals(listOf("wizard"), unknown)
        kotlin.test.assertFailsWith<IllegalArgumentException> { groups.resolve(listOf("loop")) }
        kotlin.test.assertFailsWith<IllegalArgumentException> { com.mairwunnx.worldapi.config.ConfigFile.parse("auth:\n  tokens:\n    - name: x\n      token: \"0123456789abcdef0123\"\n      scopes: [wizard]\n") }
        kotlin.test.assertFailsWith<IllegalArgumentException> { com.mairwunnx.worldapi.config.ConfigFile.parse("auth:\n  groups:\n    a: [b]\n    b: [a]\n") }
    }

    @Test
    fun `the default groups keep files write and code to the owner`() {
        val groups = com.mairwunnx.worldapi.api.Groups(Scope.defaultGroups)
        assertFalse(Scope.FILES_WRITE in groups.resolve(listOf("admin")))
        assertFalse(Scope.CODE in groups.resolve(listOf("admin")))
        assertEquals(Scope.entries.toSet(), groups.resolve(listOf("owner")))
        assertTrue(groups.resolve(listOf("viewer")).all { it in groups.resolve(listOf("moderator")) })
    }

    @Test
    fun `groups kept as 4_2 shipped them gain webhooks manage, changed ones don't`() {
        val old = Scope.defaultGroups.mapValues { (_, members) -> members - "webhooks:manage" }
        val config = com.mairwunnx.worldapi.config.WorldApiConfig(auth = com.mairwunnx.worldapi.config.Auth(groups = old + ("custom" to listOf("admin"))))
        assertTrue(Scope.WEBHOOKS_MANAGE in config.auth.resolver.resolve(listOf("admin")))
        assertTrue(Scope.WEBHOOKS_MANAGE in config.auth.resolver.resolve(listOf("owner")))
        assertTrue(Scope.WEBHOOKS_MANAGE in config.auth.resolver.resolve(listOf("custom")))
        assertFalse(Scope.WEBHOOKS_MANAGE in config.auth.resolver.resolve(listOf("moderator")))
        val changed = Scope.upgrade(mapOf("admin" to old.getValue("admin") - "code" - "files:read"))
        assertFalse("webhooks:manage" in changed.getValue("admin"))
    }
}
