package com.mairwunnx.worldapi.routes

import com.mairwunnx.worldapi.api.ModCall
import com.mairwunnx.worldapi.api.Scope
import com.mairwunnx.worldapi.api.badRequest
import com.mairwunnx.worldapi.api.boolean
import com.mairwunnx.worldapi.api.notFound
import com.mairwunnx.worldapi.api.page
import com.mairwunnx.worldapi.api.pageParams
import com.mairwunnx.worldapi.api.string
import com.mairwunnx.worldapi.game.Known
import com.mairwunnx.worldapi.mixin.FoodDataAccess
import com.mairwunnx.worldapi.game.Names
import com.mairwunnx.worldapi.game.PlayerFile
import com.mairwunnx.worldapi.game.findWorld
import com.mairwunnx.worldapi.game.hides
import com.mairwunnx.worldapi.game.knownPlayer
import com.mairwunnx.worldapi.game.onlinePlayer
import com.mairwunnx.worldapi.game.plain
import com.mairwunnx.worldapi.game.text
import com.mairwunnx.worldapi.game.toTarget
import com.mairwunnx.worldapi.game.uuidOrNull
import com.mairwunnx.worldapi.game.view
import com.mairwunnx.worldapi.game.visibleOnline
import com.mairwunnx.worldapi.game.worldName
import com.mairwunnx.worldapi.model.Done
import com.mairwunnx.worldapi.model.KickRequest
import com.mairwunnx.worldapi.model.LocationView
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.minecraft.core.Holder
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.players.NameAndId
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.level.GameType
import net.minecraft.world.level.storage.LevelResource
import java.nio.file.Files
import java.time.Instant
import java.util.Base64
import kotlin.io.path.name

private val playTime = StatRef("minecraft:custom", "minecraft:play_time")

/** What Paper's `Player.kick()` says. */
private val KICKED: Component = Component.translatable("multiplayer.disconnect.kicked")

val playerRoutes = endpoints("Players", Scope.PLAYERS_READ) {
    get(
        "/players", "Everyone who has played here",
        query = pageParams + listOf(
            boolean("online", "Only online (true) or offline (false) players"),
            string("sort", "Order of the list", values = listOf("name", "last_seen", "playtime")),
            string("search", "Only players whose name contains this, ignoring case"),
        ),
    ) {
        val online = bool("online")
        val search = query("search")
        val sort = query("sort") ?: "name"
        if (sort !in setOf("name", "last_seen", "playtime")) badRequest("sort must be name, last_seen, or playtime")
        val (limit, cursor) = limit to cursor
        val playtime = api.statistics.everyone(listOf(playTime), withAdvancements = false).associate { it.uuid to playTime.read(it.stats) }
        val live = main { api.server.playerList.players.associate { it.uuid to (it.gameProfile.name() to hides(it)) } }
        val players = everyone(api.server).mapNotNull { known ->
            val (liveName, hidden) = live[known.uuid] ?: (null to false)
            val name = liveName ?: known.name ?: return@mapNotNull null
            val file = PlayerFile.path(api.server, known.uuid).takeIf(Files::isRegularFile)?.let { times(it) }
            val here = liveName != null && !hidden
            PlayerSummary(known.uuid.toString(), name, here, iso(file?.first ?: 0), iso(if (here) System.currentTimeMillis() else file?.second ?: 0), (playtime[known.uuid] ?: 0) / 20)
        }
        players.filter { (online == null || it.online == online) && (search == null || it.name.contains(search, ignoreCase = true)) }
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
            val players = api.server.playerList.players.filterNot(::hides).sortedBy { it.gameProfile.name().lowercase() }.map {
                OnlinePlayer(
                    it.uuid.toString(), it.gameProfile.name(), it.displayName.plain(), (it.level()).worldName, it.gameMode().serializedName,
                    it.health.toDouble(), it.foodData.foodLevel, it.experienceLevel, it.connection.latency(), idleSeconds(it),
                    if (coordinates) it.view() else null,
                )
            }
            OnlinePlayers(players.size, api.server.playerList.maxPlayers, players)
        }
    }

    get(
        "/players/{player}", "A player's profile, state, and whereabouts, with more parts on request",
        description = "`include` adds parts in the same answer: inventory and enderchest (players:private; read from the playerdata file while the player is offline), " +
            "effects (online only), deaths (the last place, and the newest 5 from the history with causes and killers), sessions (the newest 5), " +
            "statistics (the minecraft:custom ones), advancements (counts and the newest 5 done), and target (online only, with players:private: " +
            "the block and the entity the player looks at within 64 blocks, and where their eyes are).",
        query = listOf(string("include", "Comma-separated parts: ${PLAYER_PARTS.joinToString()}")),
        also = Scope.PLAYERS_PRIVATE,
    ) {
        val coordinates = seesCoordinates
        val addresses = seesAddresses
        val parts = includes(PLAYER_PARTS)
        val (player, view, online) = main {
            val player = knownPlayer()
            val live = api.server.playerList.getPlayer(player.uuid)
            val hidden = live?.let(::hides) == true
            Triple(player, playerView(api.server, player, coordinates, addresses, hidden), live?.takeUnless { hidden })
        }
        withParts(view, player, online, parts)
    }

    patch<PlayerPatch, PlayerView>(
        "/players/{player}", "Change game mode, health, food, XP, flight, speed, names, and more", Scope.PLAYERS_WRITE,
        description = "The player must be online; otherwise the answer is 409 player_offline.",
    ) { patch ->
        main {
            val player = onlinePlayer()
            auditTarget = player.gameProfile.name()
            patch.apply(player, config.limits.maxMessageLength)
            playerView(api.server, Known(player.uuid, player.gameProfile.name()), coordinates = true, addresses = seesAddresses)
        }
    }

    post<TeleportRequest, TeleportResult>("/players/{player}/teleport", "Teleport to coordinates, a player, or a world spawn", Scope.PLAYERS_WRITE) { request ->
        if (listOfNotNull(request.location, request.player, request.worldSpawn).size != 1) badRequest("Give exactly one of location, player, or worldSpawn")
        main {
            val player = onlinePlayer()
            val target = when {
                request.location != null -> request.location!!.toTarget(api.server)
                request.player != null -> (visibleOnline(request.player!!) ?: notFound("player_not_found", "${request.player} isn't online")).let {
                    com.mairwunnx.worldapi.game.Target(it.level(), it.position(), it.yRot, it.xRot)
                }
                else -> (api.server.findWorld(request.worldSpawn!!) ?: notFound("world_not_found", "No world ${request.worldSpawn}")).let { level ->
                    val spawn = if (level.dimension() == api.server.respawnData.dimension()) api.server.respawnData else level.levelData.respawnData
                    com.mairwunnx.worldapi.game.Target(level, net.minecraft.world.phys.Vec3.atBottomCenterOf(spawn.pos()), spawn.yaw(), spawn.pitch())
                }
            }
            val block = target.block
            auditTarget = "${player.gameProfile.name()} -> ${target.level.worldName} ${block.x} ${block.y} ${block.z}"
            val success = player.teleportTo(target.level, target.pos.x, target.pos.y, target.pos.z, emptySet(), target.yaw, target.pitch, true)
            TeleportResult(success, player.view())
        }
    }

    post<MessageRequest, Done>("/players/{player}/message", "Send a chat message, title, action bar, or sound to one player", Scope.PLAYERS_WRITE) { request ->
        val limit = config.limits.maxMessageLength
        val message = text(request.message, request.format, limit)
        val subtitle = request.subtitle?.let { text(it, request.format, limit) }
        val sound = request.sound?.let { Identifier.tryParse(it.lowercase()) ?: badRequest("\"$it\" isn't a valid sound") }
        main {
            val player = onlinePlayer()
            auditTarget = player.gameProfile.name()
            auditDetail = request.message
            when (request.kind) {
                MessageKind.CHAT -> player.sendSystemMessage(message)
                MessageKind.ACTIONBAR -> player.sendSystemMessage(message, true)
                MessageKind.TITLE -> {
                    player.connection.send(ClientboundSetTitlesAnimationPacket(ticks(request.fadeInMillis, 500, 60_000), ticks(request.stayMillis, 3_500, 600_000), ticks(request.fadeOutMillis, 1_000, 60_000)))
                    player.connection.send(ClientboundSetSubtitleTextPacket(subtitle ?: Component.empty()))
                    player.connection.send(ClientboundSetTitleTextPacket(message))
                }
            }
            sound?.let {
                player.connection.send(ClientboundSoundPacket(Holder.direct(SoundEvent.createVariableRangeEvent(it)), SoundSource.MASTER, player.x, player.y, player.z, 1f, 1f, player.random.nextLong()))
            }
            Done()
        }
    }

    post<KickRequest, Done>("/players/{player}/kick", "Kick a player", Scope.PLAYERS_MODERATE) { request ->
        val reason = request.reason?.let { text(it, request.format, config.limits.maxMessageLength) }
        main {
            val player = onlinePlayer()
            auditTarget = player.gameProfile.name()
            auditDetail = request.reason
            player.connection.disconnect(reason ?: KICKED)
            Done()
        }
    }
}

/** Milliseconds as ticks, clamped as Paper clamps a title's times. */
private fun ticks(millis: Long?, default: Long, max: Long): Int = ((millis ?: default).coerceIn(0, max) / 50).toInt()

internal fun iso(millis: Long): String? = if (millis > 0) Instant.ofEpochMilli(millis).toString() else null

/** How long the player has done nothing, as Paper's `idleDuration` counts it. */
internal fun idleSeconds(player: ServerPlayer) = ((System.currentTimeMillis() - player.lastActionTime).coerceAtLeast(0)) / 1000

/** A playerdata file's creation and last change: when the player first joined, and when the game last saved them. */
private fun times(path: java.nio.file.Path): Pair<Long, Long>? = runCatching {
    val attributes = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java)
    attributes.creationTime().toMillis() to attributes.lastModifiedTime().toMillis()
}.getOrNull()

/** Everyone with a playerdata file, as Paper's `offlinePlayers`, with names from the server's cache. Off the server thread. */
internal fun everyone(server: MinecraftServer): List<Known> {
    val folder = server.getWorldPath(LevelResource.PLAYER_DATA_DIR)
    val cache = server.services().nameToIdCache()
    val files = if (Files.isDirectory(folder)) Files.list(folder).use { stream -> stream.filter { it.name.endsWith(".dat") }.toList() } else emptyList()
    val known = files.mapNotNull { file -> uuidOrNull(file.name.removeSuffix(".dat")) }.map { uuid -> Known(uuid, cache.get(uuid).map { it.name() }.orElse(null)) }
    val ids = known.map { it.uuid }.toSet()
    // Someone joining right now has no file yet.
    return known + server.playerList.players.filter { it.uuid !in ids }.map { Known(it.uuid, it.gameProfile.name()) }
}

/** The skin's URL from a player's profile, as Paper's `PlayerTextures` reads it. */
private fun skinUrl(player: ServerPlayer): String? = runCatching {
    val property = player.gameProfile.properties()["textures"].firstOrNull() ?: return null
    val json = Json.parseToJsonElement(String(Base64.getDecoder().decode(property.value())))
    json.jsonObject["textures"]?.jsonObject?.get("SKIN")?.jsonObject?.get("url")?.jsonPrimitive?.content
}.getOrNull()

/** With [hidden], an online player shows as offline: no live state, the saved position. Server thread. */
fun playerView(server: MinecraftServer, player: Known, coordinates: Boolean, addresses: Boolean, hidden: Boolean = false): PlayerView {
    val live = server.playerList.getPlayer(player.uuid)
    val online = live?.takeUnless { hidden }
    val file = if (online == null) PlayerFile.read(server, player.uuid) else null
    val profile = NameAndId(player.uuid, player.name ?: "")
    val lists = server.playerList
    return PlayerView(
        uuid = player.uuid.toString(),
        name = player.name ?: player.uuid.toString(),
        online = online != null,
        firstPlayed = iso(PlayerFile.path(server, player.uuid).takeIf(Files::isRegularFile)?.let { times(it)?.first } ?: 0),
        lastSeen = iso(if (online != null) System.currentTimeMillis() else file?.lastSaved ?: 0),
        skinUrl = online?.let(::skinUrl),
        op = lists.isOp(profile),
        whitelisted = lists.whiteList.isWhiteListed(profile),
        banned = lists.bans.isBanned(profile),
        // An online player's own location is live, so a hidden one gets the saved one.
        location = if (coordinates) (online?.view() ?: file?.location()) else null,
        respawnLocation = if (coordinates) (live?.respawnConfig?.respawnData()?.let { respawn ->
            server.getLevel(respawn.dimension())?.let { LocationView(it.worldName, respawn.pos().x.toDouble(), respawn.pos().y.toDouble(), respawn.pos().z.toDouble()) }
        } ?: file?.respawn()) else null,
        state = online?.let { state(it, addresses) },
    )
}

private fun state(player: ServerPlayer, addresses: Boolean) = PlayerState(
    displayName = player.displayName.plain(),
    listName = (player.tabListDisplayName ?: player.name).plain(),
    gameMode = player.gameMode().serializedName,
    health = player.health.toDouble(),
    maxHealth = player.getAttributeValue(Attributes.MAX_HEALTH),
    absorption = player.absorptionAmount.toDouble(),
    food = player.foodData.foodLevel,
    saturation = player.foodData.saturationLevel,
    exhaustion = (player.foodData as FoodDataAccess).`worldapi$exhaustion`(),
    level = player.experienceLevel,
    exp = player.experienceProgress,
    totalExperience = player.totalExperience,
    allowFlight = player.abilities.mayfly,
    flying = player.abilities.flying,
    // Bukkit's speeds are the game's doubled: walking 0.2, flying 0.1.
    walkSpeed = player.abilities.walkingSpeed * 2,
    flySpeed = player.abilities.flyingSpeed * 2,
    invulnerable = player.isPermanentlyInvulnerable,
    fireTicks = player.remainingFireTicks,
    remainingAir = player.airSupply,
    maximumAir = player.maxAirSupply,
    sleeping = player.isSleeping,
    sneaking = player.isShiftKeyDown,
    sprinting = player.isSprinting,
    ping = player.connection.latency(),
    clientBrand = null,
    locale = player.clientInformation().language().replace('_', '-'),
    idleSeconds = idleSeconds(player),
    address = if (addresses) player.ipAddress else null,
)

private fun PlayerPatch.apply(player: ServerPlayer, maxLength: Int) {
    val mode = gameMode?.let { name -> GameType.entries.firstOrNull { it.serializedName.equals(name, ignoreCase = true) } ?: badRequest("gameMode must be survival, creative, adventure, or spectator") }
    val maxHealth = player.getAttributeValue(Attributes.MAX_HEALTH)
    if (health != null && health!! !in 0.0..maxHealth) badRequest("health must be between 0 and $maxHealth")
    if (food != null && food !in 0..20) badRequest("food must be 0–20")
    if (saturation != null && saturation!! !in 0f..20f) badRequest("saturation must be 0–20")
    if (exp != null && exp!! !in 0f..1f) badRequest("exp is progress to the next level, 0–1")
    if (level != null && level!! < 0) badRequest("level can't be negative")
    listOfNotNull(walkSpeed, flySpeed).forEach { if (it !in -1f..1f) badRequest("Speeds go from -1 to 1") }
    val display = displayName?.let { text(it, format, maxLength) }
    val list = listName?.let { text(it, format, maxLength) }

    mode?.let { player.setGameMode(it) }
    health?.let { player.health = it.toFloat() }
    food?.let { player.foodData.foodLevel = it }
    saturation?.let { player.foodData.setSaturation(it) }
    exhaustion?.let { (player.foodData as FoodDataAccess).`worldapi$setExhaustion`(it) }
    level?.let { player.setExperienceLevels(it) }
    exp?.let { player.experienceProgress = it; player.setExperienceLevels(player.experienceLevel) }
    var abilities = false
    allowFlight?.let {
        player.abilities.mayfly = it
        if (!it) player.abilities.flying = false
        abilities = true
    }
    flying?.let {
        if (it && !player.abilities.mayfly) badRequest("Turn on allowFlight first")
        player.abilities.flying = it
        abilities = true
    }
    walkSpeed?.let { player.abilities.walkingSpeed = it / 2; abilities = true }
    flySpeed?.let { player.abilities.flyingSpeed = it / 2; abilities = true }
    if (abilities) player.onUpdateAbilities()
    display?.let { Names.display[player.uuid] = it }
    list?.let {
        Names.list[player.uuid] = it
        player.level().server.playerList.broadcastAll(ClientboundPlayerInfoUpdatePacket(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME, player))
    }
    invulnerable?.let { player.setPermanentlyInvulnerable(it) }
    fireTicks?.let { player.remainingFireTicks = it }
    remainingAir?.let { player.airSupply = it.coerceAtMost(player.maxAirSupply) }
}
