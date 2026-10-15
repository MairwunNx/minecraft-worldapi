package com.mairwunnx.worldapi.routes

import com.mairwunnx.worldapi.api.Scope
import com.mairwunnx.worldapi.api.badRequest
import com.mairwunnx.worldapi.api.boolean
import com.mairwunnx.worldapi.api.endpoints
import com.mairwunnx.worldapi.api.page
import com.mairwunnx.worldapi.api.pageParams
import com.mairwunnx.worldapi.api.string
import com.mairwunnx.worldapi.bukkit.findOnline
import com.mairwunnx.worldapi.bukkit.findWorld
import com.mairwunnx.worldapi.bukkit.iso
import com.mairwunnx.worldapi.bukkit.key
import com.mairwunnx.worldapi.bukkit.knownPlayer
import com.mairwunnx.worldapi.bukkit.hides
import com.mairwunnx.worldapi.bukkit.onlinePlayer
import com.mairwunnx.worldapi.bukkit.visibleOnline
import com.mairwunnx.worldapi.bukkit.plain
import com.mairwunnx.worldapi.bukkit.text
import com.mairwunnx.worldapi.bukkit.toLocation
import com.mairwunnx.worldapi.bukkit.view
import com.mairwunnx.worldapi.api.notFound
import com.mairwunnx.worldapi.model.Done
import com.mairwunnx.worldapi.model.KickRequest
import com.mairwunnx.worldapi.model.MessageKind
import com.mairwunnx.worldapi.model.MessageRequest
import com.mairwunnx.worldapi.model.OnlinePlayer
import com.mairwunnx.worldapi.model.OnlinePlayers
import com.mairwunnx.worldapi.model.PlayerPatch
import com.mairwunnx.worldapi.model.PlayerState
import com.mairwunnx.worldapi.model.PlayerSummary
import com.mairwunnx.worldapi.model.PlayerView
import com.mairwunnx.worldapi.model.TeleportRequest
import com.mairwunnx.worldapi.model.TeleportResult
import com.mairwunnx.worldapi.stats.StatRef
import kotlinx.coroutines.future.await
import net.kyori.adventure.sound.Sound
import net.kyori.adventure.text.Component
import net.kyori.adventure.title.Title
import org.bukkit.GameMode
import org.bukkit.OfflinePlayer
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Player

private val playTime = StatRef("minecraft:custom", "minecraft:play_time")

val playerRoutes = endpoints("Players") {
    get(
        "/players", "Everyone who has played here",
        query = pageParams + listOf(
            boolean("online", "Only online (true) or offline (false) players"),
            string("sort", "Order of the list", values = listOf("name", "last_seen", "playtime")),
        ),
    ) {
        val online = bool("online")
        val sort = query("sort") ?: "name"
        if (sort !in setOf("name", "last_seen", "playtime")) badRequest("sort must be name, last_seen, or playtime")
        val (limit, cursor) = limit to cursor
        val playtime = api.statistics.everyone(listOf(playTime), withAdvancements = false).associate { it.uuid to playTime.read(it.stats) }
        val players = main {
            api.server.offlinePlayers.filter { it.name != null }.map {
                val hidden = it.player?.let(::hides) == true
                PlayerSummary(it.uniqueId.toString(), it.name!!, it.isOnline && !hidden, iso(it.firstPlayed), iso(lastSeen(it, hidden)), (playtime[it.uniqueId] ?: 0) / 20)
            }
        }
        players.filter { online == null || it.online == online }
            .sortedWith(
                when (sort) {
                    "last_seen" -> compareByDescending<PlayerSummary> { it.online }.thenByDescending { it.lastSeen }
                    "playtime" -> compareByDescending { it.playtimeSeconds }
                    else -> compareBy { it.name.lowercase() }
                },
            )
            .page(limit, cursor)
    }

    get("/players/online", "Who is playing right now") {
        val coordinates = seesCoordinates
        main {
            val players = api.server.onlinePlayers.filterNot(::hides).sortedBy { it.name.lowercase() }.map {
                OnlinePlayer(
                    it.uniqueId.toString(), it.name, it.displayName().plain(), it.world.name, it.gameMode.name.lowercase(),
                    it.health, it.foodLevel, it.level, it.ping, it.idleDuration.toSeconds(),
                    if (coordinates) it.location.view() else null,
                )
            }
            OnlinePlayers(players.size, api.server.maxPlayers, players)
        }
    }

    get("/players/{player}", "A player's profile, state, and whereabouts") {
        val coordinates = seesCoordinates
        val addresses = seesAddresses
        main {
            val player = knownPlayer()
            playerView(player, coordinates, addresses, hidden = player.player?.let(::hides) == true)
        }
    }

    patch<PlayerPatch, PlayerView>(
        "/players/{player}", "Change game mode, health, food, XP, flight, speed, names, and more", Scope.WRITE_PLAYERS,
        description = "The player must be online; otherwise the answer is 409 player_offline.",
    ) { patch ->
        main {
            val player = onlinePlayer()
            auditTarget = player.name
            patch.apply(player, config.limits.maxMessageLength)
            playerView(player, coordinates = true, addresses = principal.has(Scope.ADMIN))
        }
    }

    post<TeleportRequest, TeleportResult>("/players/{player}/teleport", "Teleport to coordinates, a player, or a world spawn", Scope.WRITE_PLAYERS) { request ->
        if (listOfNotNull(request.location, request.player, request.worldSpawn).size != 1) badRequest("Give exactly one of location, player, or worldSpawn")
        val (player, target) = main {
            val player = onlinePlayer()
            val target = when {
                request.location != null -> request.location.toLocation(api.server)
                request.player != null -> (visibleOnline(request.player) ?: notFound("player_not_found", "${request.player} isn't online")).location
                else -> (api.server.findWorld(request.worldSpawn!!) ?: notFound("world_not_found", "No world ${request.worldSpawn}")).spawnLocation
            }
            player to target
        }
        auditTarget = "${player.name} -> ${target.world.name} ${target.blockX} ${target.blockY} ${target.blockZ}"
        val success = main { player.teleportAsync(target) }.await()
        TeleportResult(success, main { player.location.view() })
    }

    post<MessageRequest, Done>("/players/{player}/message", "Send a chat message, title, action bar, or sound to one player", Scope.WRITE_PLAYERS) { request ->
        val limit = config.limits.maxMessageLength
        val message = text(request.message, request.format, limit)
        val subtitle = request.subtitle?.let { text(it, request.format, limit) }
        val sound = request.sound?.let { key(it, "sound") }
        main {
            val player = onlinePlayer()
            auditTarget = player.name
            auditDetail = request.message
            when (request.kind) {
                MessageKind.CHAT -> player.sendMessage(message)
                MessageKind.ACTIONBAR -> player.sendActionBar(message)
                MessageKind.TITLE -> player.showTitle(Title.title(message, subtitle ?: Component.empty(), times(request.fadeInMillis, request.stayMillis, request.fadeOutMillis)))
            }
            sound?.let { player.playSound(Sound.sound(it, Sound.Source.MASTER, 1f, 1f)) }
            Done()
        }
    }

    post<KickRequest, Done>("/players/{player}/kick", "Kick a player", Scope.ADMIN) { request ->
        val reason = request.reason?.let { text(it, request.format, config.limits.maxMessageLength) }
        main {
            val player = onlinePlayer()
            auditTarget = player.name
            auditDetail = request.reason
            if (reason != null) player.kick(reason) else player.kick()
            Done()
        }
    }
}

/**
 * Paper's last-seen is 0 for someone online right now; use the login time then. A vanished player looks
 * offline, so they get Paper's own value, which never says they're here now.
 */
private fun lastSeen(player: OfflinePlayer, hidden: Boolean = false) = if (player.isOnline && !hidden) System.currentTimeMillis() else player.lastSeen

/** With [hidden], an online player shows as offline: no live state, the saved position. */
fun playerView(player: OfflinePlayer, coordinates: Boolean, addresses: Boolean, hidden: Boolean = false): PlayerView {
    val online = (player as? Player ?: player.player)?.takeUnless { hidden }
    return PlayerView(
        uuid = player.uniqueId.toString(),
        name = player.name ?: player.uniqueId.toString(),
        online = online != null,
        firstPlayed = iso(player.firstPlayed),
        lastSeen = iso(lastSeen(player, hidden)),
        skinUrl = online?.playerProfile?.textures?.skin?.toString(),
        op = player.isOp,
        whitelisted = player.isWhitelisted,
        banned = player.isBanned,
        // An online player's own location is live, so a hidden one gets none.
        location = if (coordinates) (online?.location ?: player.takeUnless { hidden }?.location)?.view() else null,
        respawnLocation = if (coordinates) runCatching { player.getRespawnLocation(false) }.getOrNull()?.view(withRotation = false) else null,
        state = online?.let { state(it, addresses) },
    )
}

private fun state(player: Player, addresses: Boolean) = PlayerState(
    displayName = player.displayName().plain(),
    listName = player.playerListName().plain(),
    gameMode = player.gameMode.name.lowercase(),
    health = player.health,
    maxHealth = player.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0,
    absorption = player.absorptionAmount,
    food = player.foodLevel,
    saturation = player.saturation,
    exhaustion = player.exhaustion,
    level = player.level,
    exp = player.exp,
    totalExperience = player.totalExperience,
    allowFlight = player.allowFlight,
    flying = player.isFlying,
    walkSpeed = player.walkSpeed,
    flySpeed = player.flySpeed,
    invulnerable = player.isInvulnerable,
    fireTicks = player.fireTicks,
    remainingAir = player.remainingAir,
    maximumAir = player.maximumAir,
    sleeping = player.isSleeping,
    sneaking = player.isSneaking,
    sprinting = player.isSprinting,
    ping = player.ping,
    clientBrand = player.clientBrandName,
    locale = player.locale().toLanguageTag(),
    idleSeconds = player.idleDuration.toSeconds(),
    address = if (addresses) player.address?.address?.hostAddress else null,
)

private fun PlayerPatch.apply(player: Player, maxLength: Int) {
    val mode = gameMode?.let { name -> GameMode.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: badRequest("gameMode must be survival, creative, adventure, or spectator") }
    val maxHealth = player.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
    if (health != null && health !in 0.0..maxHealth) badRequest("health must be between 0 and $maxHealth")
    if (food != null && food !in 0..20) badRequest("food must be 0–20")
    if (saturation != null && saturation !in 0f..20f) badRequest("saturation must be 0–20")
    if (exp != null && exp !in 0f..1f) badRequest("exp is progress to the next level, 0–1")
    if (level != null && level < 0) badRequest("level can't be negative")
    listOfNotNull(walkSpeed, flySpeed).forEach { if (it !in -1f..1f) badRequest("Speeds go from -1 to 1") }
    val display = displayName?.let { text(it, format, maxLength) }
    val list = listName?.let { text(it, format, maxLength) }

    mode?.let { player.gameMode = it }
    health?.let { player.health = it }
    food?.let { player.foodLevel = it }
    saturation?.let { player.saturation = it }
    exhaustion?.let { player.exhaustion = it }
    level?.let { player.level = it }
    exp?.let { player.exp = it }
    allowFlight?.let { player.allowFlight = it }
    flying?.let {
        if (it && !player.allowFlight) badRequest("Turn on allowFlight first")
        player.isFlying = it
    }
    walkSpeed?.let { player.walkSpeed = it }
    flySpeed?.let { player.flySpeed = it }
    display?.let { player.displayName(it) }
    list?.let { player.playerListName(it) }
    invulnerable?.let { player.isInvulnerable = it }
    fireTicks?.let { player.fireTicks = it }
    remainingAir?.let { player.remainingAir = it.coerceAtMost(player.maximumAir) }
}
