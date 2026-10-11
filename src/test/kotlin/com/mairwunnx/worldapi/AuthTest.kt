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
                Token("site", "site-token-0123456789", listOf(Scope.READ)),
                Token("admin", "admin-token-0123456789", listOf(Scope.ADMIN), RateLimit(10, 5)),
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
    fun `admin includes every scope but code`() {
        val admin = auth.authenticate("admin-token-0123456789")!!
        assertTrue(admin.has(Scope.READ))
        assertTrue(admin.has(Scope.WRITE_WORLD))
        assertFalse(admin.has(Scope.CODE))
        val site = auth.authenticate("site-token-0123456789")!!
        assertTrue(site.has(Scope.READ))
        assertFalse(site.has(Scope.WRITE_PLAYERS))
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
        assertEquals(setOf(Scope.READ), principal.scopes)
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
}
