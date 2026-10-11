package com.mairwunnx.worldapi.routes

import com.mairwunnx.worldapi.api.endpoints
import com.mairwunnx.worldapi.api.integer
import com.mairwunnx.worldapi.api.notFound
import com.mairwunnx.worldapi.bukkit.iso
import com.mairwunnx.worldapi.model.Aggregates
import com.mairwunnx.worldapi.model.BoardInfo
import com.mairwunnx.worldapi.model.Leaderboard
import com.mairwunnx.worldapi.model.LeaderboardEntry
import com.mairwunnx.worldapi.model.PlayerRef
import com.mairwunnx.worldapi.stats.Board
import com.mairwunnx.worldapi.stats.StatRef
import com.mairwunnx.worldapi.stats.rank
import java.time.Instant
import kotlin.math.round

private val blocksRef = StatRef("minecraft:mined", "*")
private val killedRef = StatRef("minecraft:killed", "*")
private val playTime = StatRef("minecraft:custom", "minecraft:play_time")
private val deaths = StatRef("minecraft:custom", "minecraft:deaths")

val statsRoutes = endpoints("Leaderboards") {
    get("/leaderboards", "The preset leaderboards") {
        Board.presets.values.map { BoardInfo(it.id, it.title, it.unit) }
    }

    get(
        "/leaderboards/{statistic}", "Players ranked by a statistic, ties sharing a place",
        description = "Online players count live. Use a preset or any statistic as `category:key`, such as `custom:jump`, `mined:diamond_ore`, or `killed:*`.",
        query = listOf(integer("limit", "How many places to return; the server caps it")),
    ) {
        val id = path("statistic")
        val board = Board.of(id) ?: notFound("unknown_statistic", "No leaderboard or statistic \"$id\"")
        val limit = limit
        val everyone = api.statistics.everyone(board.refs.takeIf { !board.advancements }, withAdvancements = board.advancements)
        val ranked = rank(everyone.map { it to board.score(it.stats, it.advancements) }) { it.name }
        Leaderboard(
            board.id, board.title, board.unit, ranked.size,
            ranked.take(limit).map { LeaderboardEntry(it.place, PlayerRef(it.item.uuid.toString(), it.item.name), it.value, it.item.online) },
            Instant.now().toString(),
        )
    }

    get("/aggregates", "Everyone's statistics added up") {
        val everyone = api.statistics.everyone(listOf(playTime, deaths, blocksRef, killedRef), withAdvancements = true)
        val mined = mutableMapOf<String, Long>()
        val killed = mutableMapOf<String, Long>()
        everyone.forEach { player ->
            player.stats["minecraft:mined"]?.forEach { (k, v) -> mined.merge(k, v, Long::plus) }
            player.stats["minecraft:killed"]?.forEach { (k, v) -> killed.merge(k, v, Long::plus) }
        }
        val firstJoin = api.history.firstEventAt("player.join")
        val (day, firstPlayed) = main {
            val overworld = api.server.worlds.first()
            overworld.fullTime / 24_000 + 1 to api.server.offlinePlayers.map { it.firstPlayed }.filter { it > 0 }.minOrNull()
        }
        Aggregates(
            players = everyone.size,
            hours = round(everyone.sumOf { playTime.read(it.stats) } / 72_000.0 * 10) / 10,
            blocks = mined.values.sum(),
            topBlock = mined.maxByOrNull { it.value }?.key?.removePrefix("minecraft:"),
            mobs = killed.values.sum(),
            topMob = killed.maxByOrNull { it.value }?.key?.removePrefix("minecraft:"),
            deaths = everyone.sumOf { deaths.read(it.stats) },
            advancements = everyone.sumOf { it.advancements.toLong() },
            day = day,
            since = iso(listOfNotNull(firstJoin, firstPlayed).minOrNull() ?: 0),
            at = Instant.now().toString(),
        )
    }
}
