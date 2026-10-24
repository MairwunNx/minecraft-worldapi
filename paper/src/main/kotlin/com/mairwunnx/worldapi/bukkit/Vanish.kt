package com.mairwunnx.worldapi.bukkit

import com.mairwunnx.worldapi.events.Vanished
import org.bukkit.entity.Player

/**
 * Vanish plugins (SuperVanish, PremiumVanish, Essentials, and others) mark a hidden player with Bukkit
 * metadata `vanished`. Metadata belongs to the server thread: call this there only.
 */
fun Player.isVanished(): Boolean = getMetadata("vanished").any { it.asBoolean() }

/** Adds the player to [Vanished] if they're vanished now; see [Vanished.check]. */
fun Vanished.check(player: Player) = check(player.uniqueId, player.isVanished())

fun Vanished.refresh(online: Collection<Player>) = refresh(online.filter { it.isVanished() }.mapTo(HashSet()) { it.uniqueId })
