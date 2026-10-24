package com.mairwunnx.worldapi.neoforge

import com.mairwunnx.worldapi.modcore.Mod
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.RegisterCommandsEvent
import net.neoforged.neoforge.event.server.ServerStartedEvent
import net.neoforged.neoforge.event.server.ServerStoppingEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import net.neoforged.neoforge.server.permission.events.PermissionGatherEvent

/**
 * WorldAPI on NeoForge: hands NeoForge's events to the shared mod ([Mod]), which runs WorldAPI for each server the game
 * starts, a dedicated one or the one under a singleplayer world.
 */
@net.neoforged.fml.common.Mod("worldapi")
class WorldApiMod(@Suppress("UNUSED_PARAMETER") modBus: IEventBus) {
    init {
        Mod.loader = OnNeoForge
        val bus = NeoForge.EVENT_BUS
        bus.addListener { event: ServerStartedEvent -> Mod.start(event.server) }
        bus.addListener { _: ServerStoppingEvent -> Mod.stop() }
        bus.addListener { _: ServerTickEvent.Post -> Mod.tick() }
        bus.addListener { event: RegisterCommandsEvent -> Mod.register(event.dispatcher) }
        bus.addListener(OnNeoForge::nodes)
    }
}
