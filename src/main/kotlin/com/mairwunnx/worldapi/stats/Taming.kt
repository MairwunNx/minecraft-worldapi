package com.mairwunnx.worldapi.stats

import org.bukkit.entity.Player
import org.bukkit.entity.Tameable
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityTameEvent
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.logging.Logger

/**
 * Feeds [Tamed]: every taming as it happens, and, once in a server's life, the pets already in the
 * world. Those come from the loaded mobs on the server thread and from the entity files of every world
 * off it; a pet in both counts once.
 */
class Taming(private val plugin: Plugin, private val tamed: Tamed, private val logger: Logger) : Listener {
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onTame(event: EntityTameEvent) {
        val owner = event.owner as? Player ?: return
        tamed.add(owner.uniqueId, owner.name)
    }

    /** Counts the pets in the world, unless that was done before. Call on the server thread, once the worlds are loaded. */
    fun seed() {
        if (tamed.seeded) return
        val server = plugin.server
        val loaded = server.worlds.flatMap { it.getEntitiesByClass(Tameable::class.java) }
            .mapNotNull { pet -> pet.ownerUniqueId?.takeIf { pet.isTamed }?.let { pet.uniqueId to it } }.toMap()
        val roots = server.worlds.map { it.worldFolder }
        val names = server.offlinePlayers.mapNotNull { player -> player.name?.let { player.uniqueId to it } }.toMap()
        server.scheduler.runTaskAsynchronously(plugin, Runnable {
            var skipped = 0
            val pets = HashMap<UUID, UUID>()
            EntityFiles.folders(roots).forEach { folder -> EntityFiles.pets(folder) { skipped += it }.forEach { pets[it.entity] = it.owner } }
            // Loaded mobs are newer than their files.
            pets.putAll(loaded)
            val owners = pets.values.groupingBy { it }.eachCount()
                .mapNotNull { (owner, count) -> names[owner]?.let { owner to (it to count.toLong()) } }.toMap()
            tamed.seed(owners)
            logger.info("Counted ${pets.size} pets of ${owners.size} players for the tamed board" + if (skipped > 0) "; skipped $skipped chunks it couldn't read" else "")
        })
    }
}
