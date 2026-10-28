package com.mairwunnx.worldapi.modcore

import com.mairwunnx.worldapi.events.ModEvents
import net.minecraft.advancements.AdvancementHolder
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LightningBolt
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.raid.Raid
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.trading.MerchantOffer
import net.minecraft.world.level.GameType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3

/**
 * Where the game's moments come in: each loader's entry hands in its own events, and modcore's mixins hand in what
 * neither loader has an event for, so both mods report the same moment. Every call goes to the running WorldAPI and is
 * dropped between worlds. A broken listener never breaks the game: its error goes to the log.
 */
object Hooks {
    private inline fun events(block: ModEvents.() -> Unit) {
        val events = Mod.runtime?.events ?: return
        runCatching { events.block() }.onFailure { Logs.logger.log(java.util.logging.Level.WARNING, "A WorldAPI event failed", it) }
    }

    @JvmStatic
    fun joined(player: ServerPlayer) {
        events { joined(player) }
        runCatching { Mod.runtime?.welcome(player) }
    }
    @JvmStatic fun left(player: ServerPlayer) = events { left(player) }
    @JvmStatic fun kicked(player: ServerPlayer, reason: Component) = events { kicked(player, reason) }
    @JvmStatic fun died(player: ServerPlayer, source: DamageSource, drops: Int) = events { died(player, source, drops) }
    @JvmStatic fun respawned(player: ServerPlayer, alive: Boolean) = events { respawned(player, alive) }
    @JvmStatic fun advancement(player: ServerPlayer, advancement: AdvancementHolder) = events { advancement(player, advancement) }
    @JvmStatic fun chat(player: ServerPlayer, message: String) = events { chat(player, message) }

    /** A dialog's answer: true when it was WorldAPI's, such as the webhook wizard's secret, which then goes no further. */
    @JvmStatic
    fun dialogAnswer(player: ServerPlayer, id: net.minecraft.resources.Identifier, payload: java.util.Optional<net.minecraft.nbt.Tag>): Boolean {
        if (id.namespace != "worldapi" || !id.path.startsWith("secret/")) return false
        val text = (payload.orElse(null) as? net.minecraft.nbt.CompoundTag)?.getStringOr("secret", "") ?: ""
        runCatching { Mod.runtime?.wizard?.answer(player.uuid, player.gameProfile.name(), id.path.removePrefix("secret/"), text) }
            .onFailure { Logs.logger.log(java.util.logging.Level.WARNING, "The webhook wizard failed", it) }
        return true
    }
    @JvmStatic fun command(player: ServerPlayer, command: String) = events { command(player, command) }
    @JvmStatic fun damaged(entity: LivingEntity, source: DamageSource, damage: Float) = events { damaged(entity, source, damage) }
    @JvmStatic fun entityDied(entity: LivingEntity, source: DamageSource) = events { entityDied(entity, source) }
    @JvmStatic fun gameMode(player: ServerPlayer, from: GameType, to: GameType) = events { gameMode(player, from, to) }
    @JvmStatic fun experience(player: Player, amount: Int) = events { (player as? ServerPlayer)?.let { experience(it, amount) } }
    @JvmStatic fun sleep(player: ServerPlayer, entering: Boolean) = events { sleep(player, entering) }
    @JvmStatic fun fished(player: ServerPlayer, item: ItemStack) = events { fished(player, item) }
    @JvmStatic fun crafted(player: Player, item: ItemStack) = events { (player as? ServerPlayer)?.let { crafted(it, item) } }
    @JvmStatic fun traded(player: Player?, merchant: Entity, offer: MerchantOffer) = events { (player as? ServerPlayer)?.let { traded(it, merchant, offer) } }
    @JvmStatic fun broke(player: Player, state: BlockState) = events { (player as? ServerPlayer)?.let { broke(it, state) } }
    @JvmStatic fun placed(player: Player?, state: BlockState) = events { (player as? ServerPlayer)?.let { placed(it, state) } }
    @JvmStatic fun exploded(level: ServerLevel, source: Entity?, center: Vec3, blocks: Int) = events { exploded(level, source, center, blocks) }
    @JvmStatic fun tamed(owner: Player) = events { tamed(owner) }
    @JvmStatic fun saved() = events { saved() }

    @JvmStatic
    fun enchanted(player: ServerPlayer, item: ItemStack, levels: Int) = events {
        val enchantments = item.enchantments.entrySet().associate { it.key.unwrapKey().map { key -> key.identifier().toString() }.orElse("unknown") to it.intValue } +
            (item.get(net.minecraft.core.component.DataComponents.STORED_ENCHANTMENTS)?.entrySet()?.associate { it.key.unwrapKey().map { key -> key.identifier().toString() }.orElse("unknown") to it.intValue } ?: emptyMap())
        enchanted(player, item, levels, enchantments)
    }

    /** A player moved to another world; [origin] is where they stood before, which the sampler kept. */
    @JvmStatic
    fun changedWorld(player: ServerPlayer, from: ServerLevel, to: ServerLevel) {
        val runtime = Mod.runtime ?: return
        val origin = runtime.sampler.lastPositions[player.uuid]?.takeIf { it.first == from }?.second
        events { changedWorld(player, from, to, origin) }
    }

    /** An entity came into a world: lightning has no event of its own. */
    @JvmStatic
    fun entityAdded(entity: Entity, level: ServerLevel) {
        if (entity is LightningBolt) events { lightning(level, entity.position(), (entity as com.mairwunnx.worldapi.mixin.EntityAccess.Bolt).`worldapi$visualOnly`()) }
    }

    @JvmStatic
    fun raidStarted(level: ServerLevel, player: ServerPlayer?, raid: Raid, waves: Int) = events {
        raidStarted(level, player, raid.raidOmenLevel, waves, raid.center)
    }

    @JvmStatic
    fun raidEnded(level: ServerLevel, raid: Raid, heroes: Collection<java.util.UUID>) = events {
        val result = when {
            raid.isVictory -> "victory"
            raid.isStopped -> "stop"
            else -> return@events
        }
        raidFinished(level, result, heroes.mapNotNull { level.server.playerList.getPlayer(it)?.gameProfile?.name() ?: level.server.services().nameToIdCache().get(it).map { p -> p.name() }.orElse(null) }, raid.center)
    }
}
