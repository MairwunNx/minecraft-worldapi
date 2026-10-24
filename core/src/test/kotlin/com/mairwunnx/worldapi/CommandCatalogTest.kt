package com.mairwunnx.worldapi

import com.mairwunnx.worldapi.commands.CommandCatalog
import com.mairwunnx.worldapi.model.CommandInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CommandCatalogTest {
    private val catalog = CommandCatalog(listOf(
        CommandInfo("teleport", listOf("tp"), "minecraft", usage = "/teleport <targets> <location>"),
        CommandInfo("time", source = "minecraft"),
        CommandInfo("sethome", listOf("createhome"), "Essentials", "Sets your home"),
        CommandInfo("home", listOf("homes"), "Essentials", "Teleport to your home"),
        CommandInfo("papi", listOf("placeholderapi"), "PlaceholderAPI", "PlaceholderAPI command"),
        CommandInfo("version", listOf("ver"), "bukkit"),
    ))

    @Test
    fun `a name beats a description`() {
        val found = catalog.search(catalog.commands, "home").map { it.name }
        assertEquals(listOf("home", "sethome"), found)
    }

    @Test
    fun `aliases and namespaces find a command`() {
        assertEquals("teleport", catalog.search(catalog.commands, "/tp").first().name)
        assertEquals("teleport", catalog.search(catalog.commands, "minecraft:tp").first().name)
        assertEquals("papi", catalog.search(catalog.commands, "placeholder").first().name)
    }

    @Test
    fun `each query answers on its own`() {
        val answer = catalog.answer(listOf("home", "nothing-like-it"), null, 1)
        assertEquals(6, answer.total)
        assertNull(answer.sources)
        assertEquals(listOf("home"), answer.results[0].commands.map { it.name })
        assertEquals(1, answer.results[0].more)
        assertTrue(answer.results[1].commands.isEmpty())
        assertNotNull(answer.results[1].note)
    }

    @Test
    fun `a plugin alone lists its commands, and an unknown one says which exist`() {
        assertEquals(listOf("home", "sethome"), catalog.answer(null, "essentials", null).results.single().commands.map { it.name })
        val unknown = catalog.answer(null, "Nope", null)
        assertTrue(unknown.note!!.contains("PlaceholderAPI"))
    }

    @Test
    fun `without a query the answer counts sources`() {
        val answer = catalog.answer(null, null, 2)
        assertEquals(mapOf<String?, Int>("Essentials" to 2, "PlaceholderAPI" to 1, "bukkit" to 1, "minecraft" to 2), answer.sources!!.associate { it.source to it.commands })
        assertEquals(2, answer.results.single().commands.size)
        assertEquals(4, answer.results.single().more)
    }

    @Test
    fun `plugin text loses colors and stays short`() {
        assertEquals("Hello world", CommandCatalog.text("§aHello\n  &lworld"))
        assertNull(CommandCatalog.text("  "))
        assertEquals(CommandCatalog.MAX_TEXT, CommandCatalog.text("x".repeat(500))!!.length)
    }

    @Test
    fun `the cache rebuilds when the key changes or it's cleared`() {
        var builds = 0
        val cache = CommandCatalog.Cache()
        repeat(3) { cache.get(1) { builds++; catalog } }
        cache.get(2) { builds++; catalog }
        cache.clear()
        cache.get(2) { builds++; catalog }
        assertEquals(3, builds)
    }
}
