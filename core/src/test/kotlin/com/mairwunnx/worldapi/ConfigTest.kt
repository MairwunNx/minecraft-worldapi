package com.mairwunnx.worldapi

import com.mairwunnx.worldapi.api.Scope
import com.mairwunnx.worldapi.config.ConfigFile
import com.mairwunnx.worldapi.config.WorldApiConfig
import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ConfigTest {
    private val token = "0123456789abcdef0123456789abcdef"

    @Test
    fun `the shipped config matches the defaults in code`() {
        val parsed = ConfigFile.parse(ConfigFile.defaultText().replace("{generated}", token))
        assertEquals(WorldApiConfig().copy(auth = WorldApiConfig().auth.copy(tokens = parsed.auth.tokens)), parsed)
        assertEquals(listOf("admin"), parsed.auth.tokens.single().scopes)
    }

    @Test
    fun `first start writes a fresh admin token`() {
        val dir = Files.createTempDirectory("worldapi")
        val (config, created) = ConfigFile.load(dir.resolve("config.yml"))
        assertTrue(created)
        val written = config.auth.tokens.single().token
        assertEquals(64, written.length)
        assertTrue(written in dir.resolve("config.yml").readText())
        assertNotEquals(written, ConfigFile.load(dir.resolve("other.yml")).first.auth.tokens.single().token)
    }

    @Test
    fun `a partial file falls back to defaults`() {
        val config = ConfigFile.parse("http:\n  port: 9000\n")
        assertEquals(9000, config.http.port)
        assertEquals("/api/v1", config.http.basePath)
        assertTrue(config.auth.anonymous.enabled)
    }

    @Test
    fun `scopes parse by their ids`() {
        val config = ConfigFile.parse(
            """
            auth:
              tokens:
                - name: site
                  token: "$token"
                  scopes: [read, "write:world", "write:players", code]
            """.trimIndent(),
        )
        assertEquals(listOf("read", "write:world", "write:players", "code"), config.auth.tokens.single().scopes)
        assertEquals(Scope.legacy.values.flatten().toSet() + Scope.CODE, config.auth.resolver.resolve(config.auth.tokens.single().scopes))
    }

    @Test
    fun `typos and bad values are errors`() {
        assertFailsWith<Exception> { ConfigFile.parse("htp:\n  port: 1\n") }
        assertFailsWith<Exception> { ConfigFile.parse("http:\n  port: 70000\n") }
        assertFailsWith<Exception> { ConfigFile.parse("http:\n  basePath: api/\n") }
        assertFailsWith<Exception> { ConfigFile.parse("auth:\n  tokens:\n    - { name: a, token: short, scopes: [read] }\n") }
        assertFailsWith<Exception> { ConfigFile.parse("auth:\n  tokens:\n    - { name: a, token: \"$token\", scopes: [root] }\n") }
        assertFailsWith<Exception> {
            ConfigFile.parse("auth:\n  tokens:\n    - { name: a, token: \"$token\", scopes: [read] }\n    - { name: a, token: \"${token}1\", scopes: [read] }\n")
        }
    }
}
