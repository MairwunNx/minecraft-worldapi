package com.mairwunnx.worldapi.routes

import com.mairwunnx.worldapi.api.Page
import com.mairwunnx.worldapi.api.Scope
import com.mairwunnx.worldapi.api.badRequest
import com.mairwunnx.worldapi.api.boolean
import com.mairwunnx.worldapi.api.endpoints
import com.mairwunnx.worldapi.api.notFound
import com.mairwunnx.worldapi.api.pageParams
import com.mairwunnx.worldapi.api.string
import com.mairwunnx.worldapi.bukkit.effectViews
import com.mairwunnx.worldapi.bukkit.key
import com.mairwunnx.worldapi.bukkit.knownPlayer
import com.mairwunnx.worldapi.bukkit.hides
import com.mairwunnx.worldapi.bukkit.onlinePlayer
import com.mairwunnx.worldapi.bukkit.plain
import com.mairwunnx.worldapi.bukkit.ref
import com.mairwunnx.worldapi.bukkit.registryEntry
import com.mairwunnx.worldapi.bukkit.toEffect
import com.mairwunnx.worldapi.model.AdvancementView
import com.mairwunnx.worldapi.model.Advancements
import com.mairwunnx.worldapi.model.EffectRequest
import com.mairwunnx.worldapi.model.EffectView
import com.mairwunnx.worldapi.model.PermissionPatch
import com.mairwunnx.worldapi.model.PermissionView
import com.mairwunnx.worldapi.model.PlayerStatistics
import com.mairwunnx.worldapi.model.SessionView
import io.papermc.paper.registry.RegistryKey
import org.bukkit.advancement.Advancement
import org.bukkit.entity.Player
import java.time.Instant

val playerDataRoutes = endpoints("Player data") {
    get("/players/{player}/effects", "Active potion effects") {
        main { onlinePlayer().effectViews() }
    }

    post<EffectRequest, List<EffectView>>("/players/{player}/effects", "Give a potion effect", Scope.WRITE_PLAYERS) { request ->
        main {
            val player = onlinePlayer()
            auditTarget = "${player.name} ${request.type}"
            player.addPotionEffect(request.toEffect())
            player.effectViews()
        }
    }

    delete("/players/{player}/effects/{effect}", "Remove one effect", Scope.WRITE_PLAYERS) {
        main {
            val player = onlinePlayer()
            auditTarget = "${player.name} ${path("effect")}"
            player.removePotionEffect(registryEntry(RegistryKey.MOB_EFFECT, path("effect"), "effect"))
        }
    }

    delete("/players/{player}/effects", "Remove every effect", Scope.WRITE_PLAYERS) {
        main {
            val player = onlinePlayer()
            auditTarget = player.name
            player.clearActivePotionEffects()
        }
    }

    get(
        "/players/{player}/statistics", "Every vanilla statistic, grouped by category",
        description = "Live while the player is online, from the saved stats file otherwise.",
        query = listOf(string("category", "Only this category, such as `mined` or `custom`")),
    ) {
        val player = main { knownPlayer() }
        val category = query("category")?.lowercase()?.let { "minecraft:" + it.removePrefix("minecraft:") }
        val stats = api.statistics.of(player)
        PlayerStatistics(player.ref(), main { player.isOnline }, if (category != null) stats.filterKeys { it == category } else stats)
    }

    get(
        "/players/{player}/advancements", "Advancements, done and in progress, with dates",
        description = "Lists every advancement the server has, started or not, with its title, description, and parent, so `done=false` shows what's left to do.",
        query = listOf(
            boolean("recipes", "Include recipe unlocks; off by default"),
            boolean("done", "Only finished (true) or unfinished (false) ones"),
        ),
    ) {
        val recipes = bool("recipes") ?: false
        val doneOnly = bool("done")
        val player = main { knownPlayer() }
        val online = main { player.player }
        val list = if (online != null) {
            main { liveAdvancements(online, recipes) }
        } else {
            val text = api.statistics.advancementsFile(player.uniqueId) ?: ""
            main { fileAdvancements(text, recipes) }
        }.filter { doneOnly == null || it.done == doneOnly }
        Advancements(player.ref(), list.count { it.done && !it.key.contains("recipes/") && !it.key.endsWith("/root") }, list)
    }

    post<com.mairwunnx.worldapi.api.NoBody, AdvancementView>("/players/{player}/advancements/{key...}", "Grant an advancement", Scope.WRITE_PLAYERS) {
        main {
            val player = onlinePlayer()
            val advancement = advancement(path("key"))
            auditTarget = "${player.name} ${advancement.key.asString()}"
            val progress = player.getAdvancementProgress(advancement)
            progress.remainingCriteria.toList().forEach(progress::awardCriteria)
            advancementView(advancement, player)
        }
    }

    delete("/players/{player}/advancements/{key...}", "Revoke an advancement", Scope.WRITE_PLAYERS) {
        main {
            val player = onlinePlayer()
            val advancement = advancement(path("key"))
            auditTarget = "${player.name} ${advancement.key.asString()}"
            val progress = player.getAdvancementProgress(advancement)
            progress.awardedCriteria.toList().forEach(progress::revokeCriteria)
            advancementView(advancement, player)
        }
    }

    get(
        "/players/{player}/permissions", "Operator state, and whether the player has a permission",
        query = listOf(string("node", "A permission node to check, such as `worldedit.wand`")),
    ) {
        val node = query("node")
        main {
            val player = knownPlayer()
            val online = player.player
            PermissionView(
                op = player.isOp,
                node = node,
                has = node?.let { online?.hasPermission(it) },
                effective = online?.effectivePermissions?.associate { it.permission to it.value }?.toSortedMap(),
            )
        }
    }

    patch<PermissionPatch, PermissionView>("/players/{player}/permissions", "Make a player an operator or take it away", Scope.ADMIN) { patch ->
        main {
            val player = knownPlayer()
            auditTarget = "${player.name} op=${patch.op}"
            player.isOp = patch.op
            PermissionView(op = player.isOp)
        }
    }

    get("/players/{player}/sessions", "Play sessions WorldAPI recorded, newest first", query = pageParams) {
        val (player, hidden) = main { knownPlayer().let { it to (it.player?.let(::hides) == true) } }
        val before = cursor?.let { it.toLongOrNull() ?: badRequest("Unknown cursor", "invalid_cursor") }
        val limit = limit
        // Sessions played vanished stay with admin clients, and so does the open one of a player who vanished since joining.
        val sessions = api.history.sessions(player.uniqueId.toString(), before, limit + 1, includeHidden = seesVanished)
            .filterNot { hidden && it.leftAt == null }
        val now = System.currentTimeMillis()
        Page(
            sessions.take(limit).map {
                SessionView(Instant.ofEpochMilli(it.joinedAt).toString(), it.leftAt?.let { at -> Instant.ofEpochMilli(at).toString() }, ((it.leftAt ?: now) - it.joinedAt) / 1000, it.closedByRestart)
            },
            nextCursor = if (sessions.size > limit) sessions[limit - 1].id.toString() else null,
        )
    }
}

private fun com.mairwunnx.worldapi.api.ApiCall.advancement(id: String): Advancement =
    api.server.getAdvancement(key(id, "advancement")) ?: notFound("advancement_not_found", "No advancement \"$id\"")

private fun included(advancement: Advancement, recipes: Boolean) = recipes || !advancement.key.key.startsWith("recipes/")

private fun advancementView(advancement: Advancement, player: Player): AdvancementView {
    val progress = player.getAdvancementProgress(advancement)
    val dates = progress.awardedCriteria.mapNotNull { progress.getDateAwarded(it) }
    return view(advancement, progress.isDone, dates.maxOrNull()?.toInstant(), progress.awardedCriteria.size, advancement.criteria.size)
}

internal fun view(advancement: Advancement, done: Boolean, at: Instant?, criteriaDone: Int, criteriaTotal: Int): AdvancementView {
    val display = advancement.display
    return AdvancementView(
        key = advancement.key.asString(),
        title = display?.title()?.plain(),
        description = display?.description()?.plain(),
        frame = display?.frame()?.name?.lowercase(),
        done = done,
        doneAt = if (done) at?.toString() else null,
        criteriaDone = criteriaDone,
        criteriaTotal = criteriaTotal,
        parent = advancement.parent?.key?.asString(),
    )
}

private fun liveAdvancements(player: Player, recipes: Boolean): List<AdvancementView> {
    val list = mutableListOf<AdvancementView>()
    player.server.advancementIterator().forEach { if (included(it, recipes)) list += advancementView(it, player) }
    return list.sortedBy { it.key }
}

/** Offline players: the advancements file has criteria with dates, the server has titles. */
private fun com.mairwunnx.worldapi.api.ApiCall.fileAdvancements(text: String, recipes: Boolean): List<AdvancementView> {
    val saved = if (text.isBlank()) emptyMap() else kotlinx.serialization.json.Json.parseToJsonElement(text).let { it as kotlinx.serialization.json.JsonObject }
    val list = mutableListOf<AdvancementView>()
    api.server.advancementIterator().forEach { advancement ->
        if (!included(advancement, recipes)) return@forEach
        val entry = saved[advancement.key.asString()] as? kotlinx.serialization.json.JsonObject
        val criteria = (entry?.get("criteria") as? kotlinx.serialization.json.JsonObject)?.mapValues { (it.value as kotlinx.serialization.json.JsonPrimitive).content } ?: emptyMap()
        val done = (entry?.get("done") as? kotlinx.serialization.json.JsonPrimitive)?.content == "true"
        val at = criteria.values.mapNotNull { parseSavedDate(it) }.maxOrNull()
        list += view(advancement, done, at, criteria.size, advancement.criteria.size)
    }
    return list.sortedBy { it.key }
}

/** Vanilla writes dates like `2026-10-11 17:03:12 +0200`. */
private fun parseSavedDate(text: String): Instant? = runCatching {
    java.time.OffsetDateTime.parse(text, java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z")).toInstant()
}.getOrNull()

