package com.mairwunnx.worldapi.modcore

import com.mairwunnx.worldapi.expansion.WorldApiExpansion
import com.mairwunnx.worldapi.expansion.WorldApiExpansions
import net.minecraft.server.MinecraftServer
import java.util.ServiceLoader
import java.util.logging.Logger

/**
 * Expansions other mods name in `META-INF/services/com.mairwunnx.worldapi.expansion.WorldApiExpansion`. Mods don't
 * come and go while the game runs, so they're found once; [WorldApiExpansions] keeps them until a host binds, which the
 * mods will do once they serve the API.
 */
object Expansions {
    @Volatile private var found = false

    fun discover(log: Logger) {
        if (found) return
        found = true
        // The game's class loader sees every mod's classes and files, on Fabric and NeoForge alike.
        ServiceLoader.load(WorldApiExpansion::class.java, MinecraftServer::class.java.classLoader).stream().forEach { provider ->
            runCatching { WorldApiExpansions.register(provider.get()) }
                .onFailure { log.warning("Couldn't start the expansion ${provider.type().name}: ${it.message ?: it.javaClass.simpleName}") }
        }
    }
}
