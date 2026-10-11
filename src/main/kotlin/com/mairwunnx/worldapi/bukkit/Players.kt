package com.mairwunnx.worldapi.bukkit

import com.mairwunnx.worldapi.api.ApiCall
import com.mairwunnx.worldapi.api.conflict
import com.mairwunnx.worldapi.api.notFound
import com.mairwunnx.worldapi.model.PlayerRef
import org.bukkit.OfflinePlayer
import org.bukkit.Server
import org.bukkit.entity.Player

fun OfflinePlayer.ref() = PlayerRef(uniqueId.toString(), name)

/** An online player by UUID or exact name. */
fun Server.findOnline(id: String): Player? = uuidOrNull(id)?.let(::getPlayer) ?: getPlayerExact(id)

/** Anyone who has played here, by UUID or name, without a web lookup. */
fun Server.findKnown(id: String): OfflinePlayer? {
    findOnline(id)?.let { return it }
    val uuid = uuidOrNull(id)
    val player = if (uuid != null) getOfflinePlayer(uuid) else getOfflinePlayerIfCached(id)
    return player?.takeIf { it.isOnline || it.hasPlayedBefore() }
}

fun ApiCall.knownPlayer(id: String = path("player")): OfflinePlayer =
    api.server.findKnown(id) ?: notFound("player_not_found", "No player \"$id\" has played here")

fun ApiCall.onlinePlayer(id: String = path("player")): Player {
    api.server.findOnline(id)?.let { return it }
    if (api.server.findKnown(id) != null) conflict("player_offline", "$id isn't online")
    notFound("player_not_found", "No player \"$id\" has played here")
}
