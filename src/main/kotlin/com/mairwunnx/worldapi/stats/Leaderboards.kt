package com.mairwunnx.worldapi.stats

import kotlin.math.round

/** How a leaderboard turns raw statistics into one number per player. */
class Board(
    val id: String,
    val title: String,
    val unit: String?,
    val refs: List<StatRef>,
    /** Raw statistic per unit: 72 000 ticks make an hour. */
    val divisor: Double = 1.0,
    val decimals: Int = 0,
    val advancements: Boolean = false,
) {
    fun score(stats: StatMap, advancementsDone: Int): Double {
        val raw = if (advancements) advancementsDone.toDouble() else refs.sumOf { it.read(stats) }.toDouble()
        val factor = Math.pow(10.0, decimals.toDouble())
        return round(raw / divisor * factor) / factor
    }

    companion object {
        private val travel = listOf(
            "walk", "sprint", "crouch", "swim", "walk_on_water", "walk_under_water", "climb", "fly", "aviate", "boat",
            "horse", "minecart", "pig", "strider", "happy_ghast", "nautilus", "camel",
        ).map { StatRef("minecraft:custom", "minecraft:${it}_one_cm") }

        private fun custom(key: String) = StatRef("minecraft:custom", "minecraft:$key")

        /** The records the site shows, by name. */
        val presets = listOf(
            Board("playtime", "Playtime", "hours", listOf(custom("play_time")), divisor = 72_000.0, decimals = 1),
            Board("diamonds", "Diamonds mined", "ores", listOf(StatRef("minecraft:mined", "minecraft:diamond_ore"), StatRef("minecraft:mined", "minecraft:deepslate_diamond_ore"))),
            Board("advancements", "Advancements", "advancements", emptyList(), advancements = true),
            Board("mobs", "Mobs killed", "mobs", listOf(custom("mob_kills"))),
            Board("travel", "Distance traveled", "km", travel, divisor = 100_000.0, decimals = 1),
            Board("deaths", "Deaths", "deaths", listOf(custom("deaths"))),
            Board("blocks", "Blocks mined", "blocks", listOf(StatRef("minecraft:mined", "*"))),
            Board("fish", "Fish caught", "fish", listOf(custom("fish_caught"))),
            // Damage is counted in tenths of a point, and a heart is two points.
            Board("damage", "Damage dealt", "hearts", listOf(custom("damage_dealt")), divisor = 20.0),
        ).associateBy { it.id }

        /** A preset by name, or any single statistic such as `custom:jump` or `killed:*`. */
        fun of(id: String): Board? = presets[id.lowercase()] ?: StatRef.parse(id)?.let { Board(it.toString(), it.toString(), null, listOf(it)) }
    }
}

data class Ranked<T>(val place: Int, val item: T, val value: Double)

/** Highest first; players with equal values share a place, and the next place skips ahead. */
fun <T> rank(scores: List<Pair<T, Double>>, name: (T) -> String): List<Ranked<T>> {
    val sorted = scores.filter { it.second > 0 }.sortedWith(compareBy({ -it.second }, { name(it.first).lowercase() }))
    val ranked = mutableListOf<Ranked<T>>()
    sorted.forEachIndexed { index, (item, value) ->
        val place = ranked.lastOrNull()?.takeIf { it.value == value }?.place ?: (index + 1)
        ranked += Ranked(place, item, value)
    }
    return ranked
}
