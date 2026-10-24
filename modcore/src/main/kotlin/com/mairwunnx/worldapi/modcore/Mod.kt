package com.mairwunnx.worldapi.modcore

import com.mojang.brigadier.CommandDispatcher
import net.minecraft.commands.CommandSourceStack
import net.minecraft.server.MinecraftServer

/**
 * The mod both loaders share: each loader's entry sets [loader] and hands in the game's moments. WorldAPI starts for
 * every server the game starts, a dedicated one or the one under a singleplayer world, and stops with it.
 */
object Mod {
    lateinit var loader: Loader

    /** WorldAPI for the server running now, or null between worlds and before the first. */
    @Volatile
    var runtime: Runtime? = null
        private set

    fun start(server: MinecraftServer) {
        runtime?.stop()
        ExpansionMods.discover(Logs.logger)
        runtime = runCatching { Runtime.start(server, loader, Logs.logger) }
            .onFailure { Logs.logger.log(java.util.logging.Level.SEVERE, "WorldAPI couldn't start", it) }
            .getOrNull()
    }

    fun stop() {
        val current = runtime ?: return
        runtime = null
        runCatching { current.stop() }.onFailure { Logs.logger.log(java.util.logging.Level.WARNING, "WorldAPI didn't stop cleanly", it) }
    }

    fun tick() {
        runtime?.tick()
    }

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(WorldApiCommand.build())
    }
}
