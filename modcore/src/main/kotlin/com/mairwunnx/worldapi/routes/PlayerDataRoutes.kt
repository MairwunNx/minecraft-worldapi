package com.mairwunnx.worldapi.routes

import com.mairwunnx.worldapi.api.ModCall
import com.mairwunnx.worldapi.api.Page
import com.mairwunnx.worldapi.api.Scope
import com.mairwunnx.worldapi.api.badRequest
import com.mairwunnx.worldapi.api.boolean
import com.mairwunnx.worldapi.api.notFound
import com.mairwunnx.worldapi.api.pageParams
import com.mairwunnx.worldapi.api.string
import com.mairwunnx.worldapi.game.effect
import com.mairwunnx.worldapi.game.effectViews
import com.mairwunnx.worldapi.game.hides
import com.mairwunnx.worldapi.game.knownPlayer
import com.mairwunnx.worldapi.game.onlinePlayer
import com.mairwunnx.worldapi.game.plain
import com.mairwunnx.worldapi.game.toEffect
import com.mairwunnx.worldapi.model.AdvancementView
import com.mairwunnx.worldapi.model.Advancements
import com.mairwunnx.worldapi.model.EffectRequest
import com.mairwunnx.worldapi.model.EffectView
import com.mairwunnx.worldapi.model.PermissionPatch
import com.mairwunnx.worldapi.model.PermissionView
import com.mairwunnx.worldapi.model.PlayerStatistics
import com.mairwunnx.worldapi.model.SessionView
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.minecraft.advancements.AdvancementHolder
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.players.NameAndId
import java.time.Instant

val playerDataRoutes = endpoints("Player data", Scope.PLAYERS_READ) {
    get("/players/{player}/effects", "Active potion effects") {
        main { onlinePlayer().effectViews() }
    }

    post<EffectRequest, List<EffectView>>("/players/{player}/effects", "Give a potion effect", Scope.PLAYERS_WRITE) { request ->
        main {
            val player = onlinePlayer()
            auditTarget = "${player.gameProfile.name()} ${request.type}"
            val effect = request.toEffect()
            // As Bukkit's addPotionEffect: the new effect replaces one of the same type.
            player.removeEffect(effect.effect)
            player.addEffect(effect)
            player.effectViews()
        }
    }

    delete("/players/{player}/effects/{effect}", "Remove one effect", Scope.PLAYERS_WRITE) {
        main {
            val player = onlinePlayer()
            auditTarget = "${player.gameProfile.name()} ${path("effect")}"
            player.removeEffect(effect(path("effect")))
            Unit
        }
    }

    delete("/players/{player}/effects", "Remove every effect", Scope.PLAYERS_WRITE) {
        main {
            val player = onlinePlayer()
            auditTarget = player.gameProfile.name()
            player.removeAllEffects()
        }
    }

    get(
        "/players/{player}/statistics", "Every vanilla statistic, grouped by category",
        description = "Live while the player is online, from the saved stats file otherwise.",
        query = listOf(string("category", "Only this category, such as `mined` or `custom`")),
    ) {
        val player = main { knownPlayer() }
        val category = query("category")?.lowercase()?.let { "minecraft:" + it.removePrefix("minecraft:") }
        val stats = api.statistics.of(player.uuid)
        PlayerStatistics(player.ref(), main { api.server.playerList.getPlayer(player.uuid) != null }, if (category != null) stats.filterKeys { it == category } else stats)
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
        val online = main { api.server.playerList.getPlayer(player.uuid) }
        val list = if (online != null) {
            main { liveAdvancements(api.server, online, recipes) }
        } else {
            val text = api.statistics.advancementsFile(player.uuid) ?: ""
            main { fileAdvancements(api.server, text, recipes) }
        }.filter { doneOnly == null || it.done == doneOnly }
        Advancements(player.ref(), list.count { it.done && !it.key.contains("recipes/") && !it.key.endsWith("/root") }, list)
    }

    post<com.mairwunnx.worldapi.api.NoBody, AdvancementView>("/players/{player}/advancements/{key...}", "Grant an advancement", Scope.PLAYERS_WRITE) {
        main {
            val player = onlinePlayer()
            val advancement = advancement(path("key"))
            auditTarget = "${player.gameProfile.name()} ${advancement.id()}"
            val progress = player.advancements.getOrStartProgress(advancement)
            progress.remainingCriteria.toList().forEach { player.advancements.award(advancement, it) }
            advancementView(advancement, player)
        }
    }

    delete("/players/{player}/advancements/{key...}", "Revoke an advancement", Scope.PLAYERS_WRITE) {
        main {
            val player = onlinePlayer()
            val advancement = advancement(path("key"))
            auditTarget = "${player.gameProfile.name()} ${advancement.id()}"
            val progress = player.advancements.getOrStartProgress(advancement)
            progress.completedCriteria.toList().forEach { player.advancements.revoke(advancement, it) }
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
            val online = api.server.playerList.getPlayer(player.uuid)
            PermissionView(
                op = api.server.playerList.isOp(NameAndId(player.uuid, player.name ?: "")),
                node = node,
                // A permissions mod answers, else the default a mod would use: operators at level 2.
                has = node?.let { online?.let { live -> api.loader.permission(live.createCommandSourceStack(), it, live.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER)) } },
                effective = null,
            )
        }
    }

    patch<PermissionPatch, PermissionView>("/players/{player}/permissions", "Make a player an operator or take it away", Scope.PLAYERS_MODERATE) { patch ->
        main {
            val player = knownPlayer()
            val profile = NameAndId(player.uuid, player.name ?: "")
            auditTarget = "${player.name} op=${patch.op}"
            if (patch.op) api.server.playerList.op(profile) else api.server.playerList.deop(profile)
            PermissionView(op = api.server.playerList.isOp(profile))
        }
    }

    get("/players/{player}/sessions", "Play sessions WorldAPI recorded, newest first", query = pageParams) {
        val (player, hidden) = main { knownPlayer().let { it to (api.server.playerList.getPlayer(it.uuid)?.let(::hides) == true) } }
        val before = cursor?.let { it.toLongOrNull() ?: badRequest("Unknown cursor", "invalid_cursor") }
        val limit = limit.coerceAtLeast(1)
        // Sessions played vanished stay with admin clients, and so does the open one of a player who vanished since joining.
        val sessions = api.history.sessions(player.uuid.toString(), before, limit + 1, includeHidden = seesVanished)
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

private fun ModCall.advancement(id: String): AdvancementHolder {
    val key = Identifier.tryParse(id.lowercase()) ?: badRequest("\"$id\" isn't a valid advancement")
    return api.server.advancements.get(key) ?: notFound("advancement_not_found", "No advancement \"$id\"")
}

private fun included(advancement: AdvancementHolder, recipes: Boolean) = recipes || !advancement.id().path.startsWith("recipes/")

private fun advancementView(advancement: AdvancementHolder, player: ServerPlayer): AdvancementView {
    val progress = player.advancements.getOrStartProgress(advancement)
    val completed = progress.completedCriteria.toList()
    val dates = completed.mapNotNull { progress.getCriterion(it)?.obtained }
    return view(advancement, progress.isDone, dates.maxOrNull(), completed.size, advancement.value().criteria().size)
}

internal fun view(advancement: AdvancementHolder, done: Boolean, at: Instant?, criteriaDone: Int, criteriaTotal: Int): AdvancementView {
    val display = advancement.value().display().orElse(null)
    return AdvancementView(
        key = advancement.id().toString(),
        title = display?.title?.plain(),
        description = display?.description?.plain(),
        frame = display?.type?.serializedName,
        done = done,
        doneAt = if (done) at?.toString() else null,
        criteriaDone = criteriaDone,
        criteriaTotal = criteriaTotal,
        parent = advancement.value().parent().map { it.toString() }.orElse(null),
    )
}

internal fun liveAdvancements(server: MinecraftServer, player: ServerPlayer, recipes: Boolean): List<AdvancementView> =
    server.advancements.allAdvancements.filter { included(it, recipes) }.map { advancementView(it, player) }.sortedBy { it.key }

/** Offline players: the advancements file has criteria with dates, the server has titles. */
internal fun fileAdvancements(server: MinecraftServer, text: String, recipes: Boolean): List<AdvancementView> {
    val saved = if (text.isBlank()) emptyMap() else Json.parseToJsonElement(text) as JsonObject
    return server.advancements.allAdvancements.filter { included(it, recipes) }.map { advancement ->
        val entry = saved[advancement.id().toString()] as? JsonObject
        val criteria = (entry?.get("criteria") as? JsonObject)?.mapValues { (it.value as JsonPrimitive).content } ?: emptyMap()
        val done = (entry?.get("done") as? JsonPrimitive)?.content == "true"
        val at = criteria.values.mapNotNull { parseSavedDate(it) }.maxOrNull()
        view(advancement, done, at, criteria.size, advancement.value().criteria().size)
    }.sortedBy { it.key }
}

/** Vanilla writes dates like `2026-10-11 17:03:12 +0200`. */
private fun parseSavedDate(text: String): Instant? = runCatching {
    java.time.OffsetDateTime.parse(text, java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z")).toInstant()
}.getOrNull()
