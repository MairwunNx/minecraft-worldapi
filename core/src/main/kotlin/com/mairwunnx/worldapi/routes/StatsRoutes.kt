package com.mairwunnx.worldapi.routes

import com.mairwunnx.worldapi.api.ApiCall
import com.mairwunnx.worldapi.api.Scope
import com.mairwunnx.worldapi.api.endpoints
import com.mairwunnx.worldapi.api.integer
import com.mairwunnx.worldapi.api.notFound
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

val statsRoutes = endpoints("Leaderboards", Scope.PLAYERS_READ) {
    get("/leaderboards", "The preset leaderboards") {
        Board.presets.values.map { BoardInfo(it.id, it.title, it.unit) }
    }

    get(
        "/leaderboards/{statistic}", "Players ranked by a statistic, ties sharing a place",
        description = "Online players count live. Use a preset or any statistic as `category:key`, such as `custom:jump`, `mined:diamond_ore`, or `killed:*`. The `experience` preset starts each player from the game's `XpTotal` (experience since their last death, so a lower bound) and adds every pickup after that, since vanilla keeps no lifetime statistic. The `tamed` preset starts from the pets each player owns in the world, counted once, and adds every taming after that.",
        query = listOf(integer("limit", "How many places to return; the server caps it")),
    ) {
        val id = path("statistic")
        val board = Board.of(id) ?: notFound("unknown_statistic", "No leaderboard or statistic \"$id\"")
        val limit = limit
        val entries = if (board.tamed) {
            val online = visibleOnline()
            val ranked = rank(api.tamed.totals().map { it to it.tamed.toDouble() }) { it.name }
            ranked.map { LeaderboardEntry(it.place, PlayerRef(it.item.uuid.toString(), it.item.name), it.value, it.item.uuid in online) }
        } else if (board.experience) {
            val online = visibleOnline()
            val ranked = rank(api.experience.totals().map { it to it.points.toDouble() }) { it.name }
            ranked.map { LeaderboardEntry(it.place, PlayerRef(it.item.uuid.toString(), it.item.name), it.value, it.item.uuid in online) }
        } else {
            val everyone = api.statistics.everyone(board.refs.takeIf { !board.advancements }, withAdvancements = board.advancements)
            val ranked = rank(everyone.map { it to board.score(it.stats, it.advancements) }) { it.name }
            val hidden = hiddenOnline()
            ranked.map { LeaderboardEntry(it.place, PlayerRef(it.item.uuid.toString(), it.item.name), it.value, it.item.online && it.item.uuid !in hidden) }
        }
        Leaderboard(board.id, board.title, board.unit, entries.size, entries.take(limit), Instant.now().toString())
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
        val (day, firstPlayed) = api.calendar()
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

/** Online players this caller may see: vanished ones only for moderators. */
private suspend fun ApiCall<*>.visibleOnline() = api.online().filter { (_, vanished) -> seesVanished || !vanished }.map { it.first }.toSet()

private suspend fun ApiCall<*>.hiddenOnline() = api.online().filter { (_, vanished) -> !seesVanished && vanished }.map { it.first }.toSet()

private fun iso(millis: Long): String? = if (millis > 0) Instant.ofEpochMilli(millis).toString() else null
