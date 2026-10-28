package com.mairwunnx.worldapi.neoforge

import com.mairwunnx.worldapi.modcore.Hooks
import com.mairwunnx.worldapi.modcore.Mod
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.neoforged.bus.api.EventPriority
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.RegisterCommandsEvent
import net.neoforged.neoforge.event.ServerChatEvent
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.level.block.BreakBlockEvent
import net.neoforged.neoforge.event.server.ServerStartedEvent
import net.neoforged.neoforge.event.server.ServerStoppingEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent

/**
 * WorldAPI on NeoForge: hands NeoForge's events to the shared mod ([Mod], [Hooks]), which runs WorldAPI for each server
 * the game starts, a dedicated one or the one under a singleplayer world. Events another mod may cancel are read last,
 * once nobody did.
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

        bus.addListener { event: PlayerEvent.PlayerLoggedInEvent -> (event.entity as? ServerPlayer)?.let(Hooks::joined) }
        bus.addListener { event: PlayerEvent.PlayerLoggedOutEvent -> (event.entity as? ServerPlayer)?.let(Hooks::left) }
        bus.addListener(EventPriority.HIGHEST) { event: ServerChatEvent -> if (!Hooks.allowChat(event.player, event.rawText)) event.isCanceled = true }
        bus.addListener(EventPriority.LOWEST) { event: ServerChatEvent -> if (!event.isCanceled) Hooks.chat(event.player, event.rawText) }
        bus.addListener { event: PlayerEvent.PlayerRespawnEvent -> (event.entity as? ServerPlayer)?.let { Hooks.respawned(it, event.isEndConquered) } }
        bus.addListener { event: PlayerEvent.PlayerChangedDimensionEvent ->
            val player = event.entity as? ServerPlayer ?: return@addListener
            val from = player.level().server.getLevel(event.from) ?: return@addListener
            val to = player.level().server.getLevel(event.to) ?: return@addListener
            Hooks.changedWorld(player, from, to)
        }
        bus.addListener { event: LivingDamageEvent.Post -> Hooks.damaged(event.entity, event.source, event.inflictedDamage) }
        bus.addListener(EventPriority.LOWEST) { event: LivingDeathEvent -> if (!event.isCanceled && event.entity !is ServerPlayer) Hooks.entityDied(event.entity, event.source) }
        bus.addListener(EventPriority.LOWEST) { event: BreakBlockEvent -> if (!event.isCanceled) Hooks.broke(event.player, event.state) }
        bus.addListener { event: EntityJoinLevelEvent -> if (!event.loadedFromDisk()) (event.level as? ServerLevel)?.let { Hooks.entityAdded(event.entity, it) } }
    }
}
