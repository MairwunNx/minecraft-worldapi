package com.mairwunnx.worldapi.fabric

import com.mairwunnx.worldapi.modcore.Hooks
import com.mairwunnx.worldapi.modcore.Mod
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.server.level.ServerPlayer

/**
 * WorldAPI on Fabric: hands Fabric API's events to the shared mod ([Mod], [Hooks]), which runs WorldAPI for each server
 * the game starts, a dedicated one or the one under a singleplayer world. What Fabric API has no event for, modcore's
 * mixins catch on both loaders.
 */
class WorldApiMod : ModInitializer {
    override fun onInitialize() {
        Mod.loader = OnFabric
        ServerLifecycleEvents.SERVER_STARTED.register(Mod::start)
        ServerLifecycleEvents.SERVER_STOPPING.register { Mod.stop() }
        ServerTickEvents.END_SERVER_TICK.register { Mod.tick() }
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ -> Mod.register(dispatcher) }

        ServerPlayConnectionEvents.JOIN.register { handler, _, _ -> Hooks.joined(handler.player) }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ -> Hooks.left(handler.player) }
        ServerMessageEvents.CHAT_MESSAGE.register { message, sender, _ -> Hooks.chat(sender, message.signedContent()) }
        ServerPlayerEvents.AFTER_RESPAWN.register { _, player, alive -> Hooks.respawned(player, alive) }
        ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register { player, origin, destination -> Hooks.changedWorld(player, origin, destination) }
        ServerLivingEntityEvents.AFTER_DAMAGE.register { entity, source, _, taken, _ -> Hooks.damaged(entity, source, taken) }
        ServerLivingEntityEvents.AFTER_DEATH.register { entity, source -> Hooks.entityDied(entity, source) }
        PlayerBlockBreakEvents.AFTER.register { _, player, _, state, _ -> if (player is ServerPlayer) Hooks.broke(player, state) }
        ServerEntityEvents.ENTITY_LOAD.register { entity, level -> Hooks.entityAdded(entity, level) }
    }
}
