package com.mairwunnx.worldapi.fabric

import com.mairwunnx.worldapi.modcore.Mod
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents

/**
 * WorldAPI on Fabric: hands Fabric API's events to the shared mod ([Mod]), which runs WorldAPI for each server the game
 * starts, a dedicated one or the one under a singleplayer world.
 */
class WorldApiMod : ModInitializer {
    override fun onInitialize() {
        Mod.loader = OnFabric
        ServerLifecycleEvents.SERVER_STARTED.register(Mod::start)
        ServerLifecycleEvents.SERVER_STOPPING.register { Mod.stop() }
        ServerTickEvents.END_SERVER_TICK.register { Mod.tick() }
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ -> Mod.register(dispatcher) }
    }
}
