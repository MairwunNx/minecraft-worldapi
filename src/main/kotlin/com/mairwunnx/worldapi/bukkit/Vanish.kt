package com.mairwunnx.worldapi.bukkit

import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Vanish plugins (SuperVanish, PremiumVanish, Essentials, and others) mark a hidden player with Bukkit
 * metadata `vanished`. Metadata belongs to the server thread: call this there only.
 */
fun Player.isVanished(): Boolean = getMetadata("vanished").any { it.asBoolean() }

/**
 * Who is vanished, readable from any thread. Vanish plugins share no event, so the server thread keeps
 * this current: a refresh every few ticks, and a check right before an event about a player goes out.
 */
class Vanished {
    private val uuids = ConcurrentHashMap.newKeySet<UUID>()

    operator fun contains(uuid: UUID) = uuid in uuids

    operator fun contains(uuid: String) = uuidOrNull(uuid)?.let { it in uuids } ?: false

    /**
     * Adds the player if they're vanished now. It never removes anyone: a vanish plugin may drop its
     * metadata before WorldAPI hears about the quit, and the refresh takes care of players who reappear.
     */
    fun check(player: Player) {
        if (player.isVanished()) uuids += player.uniqueId
    }

    fun add(uuid: UUID) {
        uuids += uuid
    }

    fun refresh(online: Collection<Player>) {
        val now = online.filter { it.isVanished() }.mapTo(HashSet()) { it.uniqueId }
        uuids.retainAll(now)
        uuids.addAll(now)
    }
}
