package com.mairwunnx.worldapi.game

import com.mairwunnx.worldapi.api.ModCall
import com.mairwunnx.worldapi.api.conflict
import com.mairwunnx.worldapi.api.notFound
import com.mairwunnx.worldapi.model.PlayerRef
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.storage.LevelResource
import java.nio.file.Files
import java.util.UUID

fun uuidOrNull(value: String): UUID? = runCatching { UUID.fromString(value) }.getOrNull()

/** Someone who has played here: online, or with a playerdata file, as Paper's `hasPlayedBefore` reads it. */
class Known(val uuid: UUID, val name: String?) {
    fun ref() = PlayerRef(uuid.toString(), name)
}

fun ServerPlayer.ref() = PlayerRef(uuid.toString(), gameProfile.name())

fun ServerPlayer.known() = Known(uuid, gameProfile.name())

/** An online player by UUID or exact name. */
fun MinecraftServer.findOnline(id: String): ServerPlayer? = uuidOrNull(id)?.let(playerList::getPlayer) ?: playerList.getPlayer(id)

/** Whether a player has a playerdata file, so they joined at least once. */
fun MinecraftServer.playedBefore(uuid: UUID) = Files.isRegularFile(getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve("$uuid.dat"))

/** Anyone who has played here, by UUID or name, without a web lookup. */
fun MinecraftServer.findKnown(id: String): Known? {
    findOnline(id)?.let { return it.known() }
    val cache = services().nameToIdCache()
    val uuid = uuidOrNull(id)
    val profile = if (uuid != null) cache.get(uuid).orElse(null) else cache.get(id).orElse(null)
    val found = uuid ?: profile?.id() ?: return null
    return Known(found, profile?.name()).takeIf { playedBefore(found) }
}

fun ModCall.knownPlayer(id: String = path("player")): Known =
    api.server.findKnown(id) ?: notFound("player_not_found", "No player \"$id\" has played here")

/** Whether this client must not see [player]: vanished, and the client isn't admin. Server thread only. */
fun ModCall.hides(player: ServerPlayer) = !seesVanished && api.loader.vanished(player)

/** An online player this client may see, by UUID or exact name; a vanished one counts as offline. */
fun ModCall.visibleOnline(id: String): ServerPlayer? = api.server.findOnline(id)?.takeUnless(::hides)

fun ModCall.onlinePlayer(id: String = path("player")): ServerPlayer {
    visibleOnline(id)?.let { return it }
    if (api.server.findKnown(id) != null) conflict("player_offline", "$id isn't online")
    notFound("player_not_found", "No player \"$id\" has played here")
}
