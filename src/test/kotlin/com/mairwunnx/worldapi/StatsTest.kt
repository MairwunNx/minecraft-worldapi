package com.mairwunnx.worldapi

import com.mairwunnx.worldapi.stats.Board
import com.mairwunnx.worldapi.stats.StatRef
import com.mairwunnx.worldapi.stats.Statistics
import com.mairwunnx.worldapi.stats.rank
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StatsTest {
    private val stats = mapOf(
        "minecraft:custom" to mapOf("minecraft:play_time" to 144_000L, "minecraft:damage_dealt" to 205L, "minecraft:walk_one_cm" to 250_000L),
        "minecraft:mined" to mapOf("minecraft:stone" to 10L, "minecraft:diamond_ore" to 2L, "minecraft:deepslate_diamond_ore" to 3L),
    )

    @Test
    fun `statistic refs parse in both spellings`() {
        assertEquals(StatRef("minecraft:custom", "minecraft:jump"), StatRef.parse("custom:jump"))
        assertEquals(StatRef("minecraft:mined", "*"), StatRef.parse("mined:*"))
        assertEquals(StatRef("minecraft:killed", "minecraft:zombie"), StatRef.parse("minecraft:killed/minecraft:zombie"))
        assertNull(StatRef.parse("nope:jump"))
        assertNull(StatRef.parse("custom:"))
        assertNull(StatRef.parse("custom:a b"))
        assertEquals("mined:diamond_ore", StatRef.parse("mined:diamond_ore").toString())
    }

    @Test
    fun `refs read single values and whole categories`() {
        assertEquals(15, StatRef.parse("mined:*")!!.read(stats))
        assertEquals(0, StatRef.parse("killed:*")!!.read(stats))
        assertEquals(144_000, StatRef.parse("custom:play_time")!!.read(stats))
    }

    @Test
    fun `presets convert units like the site does`() {
        assertEquals(2.0, Board.of("playtime")!!.score(stats, 0))
        assertEquals(5.0, Board.of("diamonds")!!.score(stats, 0))
        assertEquals(15.0, Board.of("blocks")!!.score(stats, 0))
        assertEquals(10.0, Board.of("damage")!!.score(stats, 0))
        assertEquals(2.5, Board.of("travel")!!.score(stats, 0))
        assertEquals(7.0, Board.of("advancements")!!.score(stats, 7))
        assertEquals("custom:jump", Board.of("custom:jump")!!.id)
        assertNull(Board.of("bogus"))
    }

    @Test
    fun `ties share a place and the next place skips ahead`() {
        val ranked = rank(listOf("b" to 5.0, "a" to 5.0, "c" to 9.0, "d" to 1.0, "e" to 0.0)) { it }
        assertEquals(listOf(1 to "c", 2 to "a", 2 to "b", 4 to "d"), ranked.map { it.place to it.item })
    }

    @Test
    fun `advancement files count finished ones without recipes and roots`() {
        val text = """
            {
              "minecraft:story/root": {"criteria": {}, "done": true},
              "minecraft:story/mine_stone": {"criteria": {}, "done": true},
              "minecraft:recipes/misc/torch": {"criteria": {}, "done": true},
              "minecraft:story/smelt_iron": {"criteria": {}, "done": false},
              "DataVersion": 4000
            }
        """.trimIndent()
        assertEquals(1, Statistics::class.java.getDeclaredConstructor(org.bukkit.Server::class.java, com.mairwunnx.worldapi.core.MainThread::class.java)
            .newInstance(fakeServer(), com.mairwunnx.worldapi.core.MainThread.Immediate).countDone(text))
    }
}
