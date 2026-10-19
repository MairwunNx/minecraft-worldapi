package com.mairwunnx.worldapi

import com.mairwunnx.worldapi.api.Authenticator
import com.mairwunnx.worldapi.api.Cidr
import com.mairwunnx.worldapi.api.Scope
import com.mairwunnx.worldapi.config.Auth
import com.mairwunnx.worldapi.tokens.TokenStore
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TokenTest {
    private fun store() = TokenStore(Files.createTempDirectory("tokens").resolve("tokens.yml")).apply { load() }

    @Test
    fun `issued tokens are stored as digests only`() {
        val store = store()
        val token = store.issue("site", listOf("viewer"), "MairwunNx", null)
        val file = store.tokens.single()
        assertEquals(64, token.length)
        assertEquals(TokenStore.digest(token), file.sha256)
        val path = store.javaClass.getDeclaredField("path").apply { isAccessible = true }.get(store) as java.nio.file.Path
        assertFalse(token in path.readText())
        assertTrue(TokenStore.digest(token) in path.readText())
    }

    @Test
    fun `tokens survive a reload and can be revoked`() {
        val store = store()
        val path = store.javaClass.getDeclaredField("path").apply { isAccessible = true }.get(store) as java.nio.file.Path
        store.issue("agent", listOf("viewer", "world:write"), "MairwunNx", Instant.now().plus(Duration.ofDays(1)))
        val reloaded = TokenStore(path).apply { load() }
        assertEquals(listOf("viewer", "world:write"), reloaded.tokens.single().scopes)
        assertFailsWith<IllegalArgumentException> { reloaded.issue("agent", listOf("viewer"), "x", null) }
        val before = reloaded.generation
        assertTrue(reloaded.revoke("agent"))
        assertFalse(reloaded.revoke("agent"))
        assertTrue(reloaded.generation > before)
        assertTrue(TokenStore(path).apply { load() }.tokens.isEmpty())
    }

    @Test
    fun `the authenticator accepts issued tokens until they expire`() {
        val store = store()
        val live = store.issue("live", listOf("players:write"), "MairwunNx", Instant.now().plus(Duration.ofDays(1)))
        val old = store.issue("old", listOf("viewer"), "MairwunNx", Instant.now().plus(Duration.ofSeconds(1)))
        var now = Instant.now()
        val auth = Authenticator(Auth(), store.tokens) { now }
        assertEquals(setOf(Scope.PLAYERS_WRITE), auth.authenticate(live)?.scopes)
        assertNotNull(auth.authenticate(old))
        now = now.plus(Duration.ofMinutes(1))
        assertNull(auth.authenticate(old))
        assertNotNull(auth.authenticate(live))
    }

    @Test
    fun `address ranges`() {
        val private = Cidr.parse("10.0.0.0/8")!!
        assertTrue(private.contains("10.1.2.3"))
        assertFalse(private.contains("11.0.0.1"))
        assertFalse(private.contains("::1"))
        assertTrue(Cidr.parse("172.16.0.0/12")!!.contains("172.31.255.255"))
        assertFalse(Cidr.parse("172.16.0.0/12")!!.contains("172.32.0.1"))
        assertTrue(Cidr.parse("::1/128")!!.contains("::1"))
        assertTrue(Cidr.parse("127.0.0.1")!!.contains("127.0.0.1"))
        assertNull(Cidr.parse("example.com"))
        assertNull(Cidr.parse("10.0.0.0/40"))
        assertFalse(private.contains("localhost"))
    }
}
