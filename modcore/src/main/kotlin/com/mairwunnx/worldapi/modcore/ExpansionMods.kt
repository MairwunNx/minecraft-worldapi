@file:Suppress("DEPRECATION")

package com.mairwunnx.worldapi.modcore

import com.mairwunnx.worldapi.expansion.WorldApiExpansion
import com.mairwunnx.worldapi.expansion.WorldApiExpansions
import com.mairwunnx.worldapi.extensions.ExpansionServices
import net.minecraft.server.MinecraftServer
import java.util.ServiceLoader
import java.util.logging.Logger

/**
 * Expansions other mods name in `META-INF/services/com.mairwunnx.worldapi.expansion.v2.Expansion` (API 2) or
 * `META-INF/services/com.mairwunnx.worldapi.expansion.WorldApiExpansion` (API 1). Mods don't come and go while the game
 * runs, so they're found once; the API's registries keep them until a host binds, when a server starts.
 */
object ExpansionMods {
    @Volatile private var found = false

    fun discover(log: Logger) {
        if (found) return
        found = true
        // The game's class loader sees every mod's classes and files, on Fabric and NeoForge alike.
        runCatching { ExpansionServices.find(MinecraftServer::class.java.classLoader) }
            .onFailure { log.warning("Couldn't read the mods' expansions: ${it.message ?: it.javaClass.simpleName}") }
            .getOrDefault(emptyList())
            .forEach { found ->
                found.expansion.mapCatching { it.register() }
                    .onFailure { log.warning("Couldn't start the expansion ${found.name}: ${it.message ?: it.javaClass.simpleName}") }
            }
        ServiceLoader.load(WorldApiExpansion::class.java, MinecraftServer::class.java.classLoader).stream().forEach { provider ->
            runCatching { WorldApiExpansions.register(provider.get()) }
                .onFailure { log.warning("Couldn't start the expansion ${provider.type().name}: ${it.message ?: it.javaClass.simpleName}") }
        }
    }
}
