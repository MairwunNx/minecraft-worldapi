package com.mairwunnx.worldapi.events

import com.mairwunnx.worldapi.game.Names
import com.mairwunnx.worldapi.game.plain
import com.mairwunnx.worldapi.game.playedBefore
import com.mairwunnx.worldapi.game.ref
import com.mairwunnx.worldapi.game.typeKey
import com.mairwunnx.worldapi.game.worldName
import com.mairwunnx.worldapi.modcore.Loader
import com.mairwunnx.worldapi.stats.Experience
import com.mairwunnx.worldapi.stats.Tamed
import com.mairwunnx.worldapi.store.History
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import net.minecraft.advancements.AdvancementHolder
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.boss.enderdragon.EnderDragon
import net.minecraft.world.entity.boss.wither.WitherBoss
import net.minecraft.world.entity.monster.ElderGuardian
import net.minecraft.world.entity.monster.warden.Warden
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.trading.MerchantOffer
import net.minecraft.world.level.GameType
import net.minecraft.world.level.block.BedBlock
import net.minecraft.world.level.block.RespawnAnchorBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.gamerules.GameRules
import net.minecraft.world.phys.Vec3
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The game's moments as WorldAPI events, as Paper's `Listeners` makes them from Bukkit's: each method is one Bukkit
 * event, called by the loader's own event or by a mixin in modcore once other mods had their say, so both mods see the
 * same moment. Server thread unless a method says otherwise.
 */
class ModEvents(
    private val server: MinecraftServer,
    private val loader: Loader,
    private val bus: EventBus,
    private val history: History,
    private val experience: Experience,
    private val tamed: Tamed,
    private val windows: Windows,
    private val vanished: Vanished,
    private val chatEnabled: () -> Boolean,
) {
    private val joinedAt = ConcurrentHashMap<UUID, Long>()
    private val lastDamage = ConcurrentHashMap<UUID, Long>()
    private val kicked = ConcurrentHashMap.newKeySet<UUID>()
    private var lastSave = 0L

    private fun publish(event: ApiEvent) {
        if (!bus.wanted(event.topic)) return
        // Vanish mods share no event: look at the player now, while on the server thread. Chat may arrive off it.
        if (server.isSameThread) event.player?.uuid?.let { runCatching { UUID.fromString(it) }.getOrNull() }?.let(server.playerList::getPlayer)?.let { vanished.check(it.uuid, loader.vanished(it)) }
        bus.publish(event)
    }

    /** Counts in event payloads leave vanished players out, since everyone may read them. */
    private fun visible(players: Collection<ServerPlayer>) = players.count { !loader.vanished(it) }

    fun joined(player: ServerPlayer) {
        // The game writes a new player's file only once it saves them; until then, they never played here.
        val first = !server.playedBefore(player.uuid)
        joinedAt[player.uuid] = System.currentTimeMillis()
        history.openSession(player.uuid.toString(), player.gameProfile.name(), Instant.now(), hidden = loader.vanished(player))
        // Someone who never joined since WorldAPI started counting gets their baseline now.
        experience.seed(player.uuid, player.gameProfile.name(), player.totalExperience)
        publish(player.event(Topic.PLAYER_JOIN, buildJsonObject {
            put("firstJoin", first)
            put("online", visible(server.playerList.players))
        }))
    }

    fun left(player: ServerPlayer) {
        history.closeSession(player.uuid.toString(), Instant.now())
        val since = joinedAt.remove(player.uuid)
        Names.forget(player.uuid)
        publish(player.event(Topic.PLAYER_LEAVE, buildJsonObject {
            put("reason", if (kicked.remove(player.uuid)) "kicked" else "disconnected")
            since?.let { put("sessionSeconds", (System.currentTimeMillis() - it) / 1000) }
            put("online", visible(server.playerList.players.filter { it != player }))
        }))
    }

    fun kicked(player: ServerPlayer, reason: Component) {
        // A leave follows; it says kicked, as Paper's quit reason does.
        if (!kicked.add(player.uuid)) return
        publish(player.event(Topic.PLAYER_KICK, buildJsonObject {
            put("reason", reason.plain())
            put("cause", "unknown")
        }))
    }

    /** At the end of a player's death: [drops] counts what they carried as it began, which the ground now holds. */
    fun died(player: ServerPlayer, source: DamageSource, drops: Int) {
        val keep = player.level().gameRules.get(GameRules.KEEP_INVENTORY)
        publish(player.event(Topic.PLAYER_DEATH, buildJsonObject {
            put("message", source.getLocalizedDeathMessage(player).plain())
            put("cause", source.typeHolder().unwrapKey().map { it.identifier().toString() }.orElse("minecraft:generic"))
            (source.entity ?: player.killCredit)?.let { killer ->
                putJsonObject("killer") {
                    put("type", BuiltInRegistries.ENTITY_TYPE.getKey(killer.type).toString())
                    put("name", (killer as? ServerPlayer)?.gameProfile?.name() ?: killer.customName?.plain())
                    put("uuid", killer.uuid.toString())
                }
            }
            put("level", player.experienceLevel)
            put("keepInventory", keep)
            put("drops", if (keep) 0 else drops)
            put("location", player.locationJson())
        }))
    }

    fun respawned(player: ServerPlayer, alive: Boolean) {
        val respawn = player.respawnConfig?.respawnData()
        val block: BlockState? = respawn?.let { server.getLevel(it.dimension())?.getBlockState(it.pos()) }
        publish(player.event(Topic.PLAYER_RESPAWN, buildJsonObject {
            // Leaving the End alive is Bukkit's END_PORTAL respawn.
            put("reason", if (alive) "end_portal" else "death")
            put("bed", !alive && block?.block is BedBlock)
            put("anchor", !alive && block?.block is RespawnAnchorBlock)
            put("location", player.locationJson())
        }))
    }

    fun advancement(player: ServerPlayer, advancement: AdvancementHolder) {
        // Recipes and hidden technical advancements have no display; vanilla doesn't announce them either.
        val display = advancement.value().display().orElse(null) ?: return
        publish(player.event(Topic.PLAYER_ADVANCEMENT, buildJsonObject {
            put("key", advancement.id().toString())
            put("title", display.title.plain())
            put("description", display.description.plain())
            put("frame", display.type.serializedName)
            put("announced", display.announceToChat())
        }))
    }

    /** From any thread: a loader may hand chat in off the server thread, and the bus and the store are safe from there. */
    fun chat(player: ServerPlayer, message: String) {
        if (!chatEnabled()) return
        publish(ApiEvent(Topic.PLAYER_CHAT, buildJsonObject { put("message", message) }, player.ref(), player.level().worldName, player.uuid.toString()))
    }

    fun command(player: ServerPlayer, command: String) {
        if (!bus.wanted(Topic.PLAYER_COMMAND)) return
        publish(player.event(Topic.PLAYER_COMMAND, buildJsonObject { put("command", command.removePrefix("/")) }))
    }

    fun changedWorld(player: ServerPlayer, from: ServerLevel, to: ServerLevel, origin: Vec3?) {
        publish(player.event(Topic.PLAYER_WORLD_CHANGE, buildJsonObject {
            put("from", from.worldName)
            put("to", to.worldName)
        }))
        // Between dimensions a portal is how a player travels, unless a command sent them.
        if (origin != null && bus.wanted(Topic.PLAYER_PORTAL)) {
            val cause = if (from.dimension() == net.minecraft.world.level.Level.END || to.dimension() == net.minecraft.world.level.Level.END) "end_portal" else "nether_portal"
            publish(player.event(Topic.PLAYER_PORTAL, buildJsonObject {
                put("cause", cause)
                put("from", locationJson(from, origin))
                put("to", player.locationJson())
            }))
        }
    }

    fun damaged(entity: LivingEntity, source: DamageSource, damage: Float) {
        val player = entity as? ServerPlayer ?: return
        if (!bus.wanted(Topic.PLAYER_DAMAGE)) return
        // At most four per second per player: a lava bath shouldn't flood clients.
        val now = System.currentTimeMillis()
        if (now - (lastDamage[player.uuid] ?: 0) < 250) return
        lastDamage[player.uuid] = now
        publish(player.event(Topic.PLAYER_DAMAGE, buildJsonObject {
            put("cause", source.typeHolder().unwrapKey().map { it.identifier().toString() }.orElse("minecraft:generic"))
            put("damage", damage.toDouble())
            put("health", player.health.toDouble().coerceAtLeast(0.0))
            source.directEntity?.let { put("damager", BuiltInRegistries.ENTITY_TYPE.getKey(it.type).toString()) }
        }))
    }

    /** A mob's or another entity's death; a player's goes through [died]. */
    fun entityDied(entity: LivingEntity, source: DamageSource) {
        if (entity is ServerPlayer) return
        val level = entity.level() as? ServerLevel ?: return
        val type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.type).toString()
        windows.entityDeath(level.worldName, type)
        // As Bukkit's `getKiller`: the player behind the last hit, directly or not.
        val killer = (source.entity as? ServerPlayer) ?: (entity.lastHurtByPlayer as? ServerPlayer)
        if (killer != null) {
            publish(killer.event(Topic.PLAYER_KILL, buildJsonObject {
                put("victim", type)
                entity.customName?.let { put("victimName", it.plain()) }
                put("weapon", killer.mainHandItem.typeKey)
                put("location", entity.locationJson())
            }))
        }
        if (entity is EnderDragon || entity is WitherBoss || entity is ElderGuardian || entity is Warden) {
            val participants = level.players().filter { it.distanceToSqr(entity) <= 128.0 * 128.0 }.map { it.gameProfile.name() }.sorted()
            publish(ApiEvent(Topic.ENTITY_BOSS_KILLED, buildJsonObject {
                put("boss", type)
                put("killer", killer?.gameProfile?.name())
                put("players", JsonArray(participants.map(::JsonPrimitive)))
                put("location", entity.locationJson())
            }, killer?.ref(), level.worldName))
        }
    }

    fun gameMode(player: ServerPlayer, from: GameType, to: GameType) {
        if (from == to) return
        publish(player.event(Topic.PLAYER_GAMEMODE, buildJsonObject {
            put("from", from.serializedName)
            put("to", to.serializedName)
        }))
    }

    /** Orbs, as Paper's `PlayerExpChangeEvent` counts them; `/xp` doesn't come here. */
    fun experience(player: ServerPlayer, amount: Int) {
        if (amount > 0) experience.add(player.uuid, player.gameProfile.name(), amount)
    }

    fun levelChanged(player: ServerPlayer, from: Int, to: Int) {
        publish(player.event(Topic.PLAYER_LEVEL_UP, buildJsonObject {
            put("from", from)
            put("to", to)
        }))
    }

    fun sleep(player: ServerPlayer, entering: Boolean) {
        val level = player.level()
        val sleeping = level.players().count { it.isSleeping || (entering && it == player) } - if (!entering && player.isSleeping) 1 else 0
        publish(player.event(Topic.PLAYER_SLEEP, buildJsonObject {
            put("action", if (entering) "enter" else "leave")
            put("sleeping", sleeping.coerceAtLeast(0))
            put("players", level.players().count { !it.isSpectator && !loader.vanished(it) })
        }))
    }

    fun fished(player: ServerPlayer, item: ItemStack) {
        publish(player.event(Topic.PLAYER_FISH, buildJsonObject {
            put("item", item.json(player.level()) ?: JsonPrimitive(null as String?))
        }))
    }

    fun crafted(player: ServerPlayer, item: ItemStack) {
        if (item.isEmpty) return
        windows.craft(player.ref(), player.level().worldName, item.typeKey, item.count)
    }

    fun enchanted(player: ServerPlayer, item: ItemStack, levelCost: Int, enchantments: Map<String, Int>) {
        publish(player.event(Topic.PLAYER_ENCHANT, buildJsonObject {
            put("item", item.typeKey)
            put("levelCost", levelCost)
            putJsonObject("enchantments") { enchantments.forEach { (key, level) -> put(key, level) } }
        }))
    }

    fun traded(player: ServerPlayer, merchant: Entity, offer: MerchantOffer) {
        publish(player.event(Topic.PLAYER_TRADE, buildJsonObject {
            put("merchant", BuiltInRegistries.ENTITY_TYPE.getKey(merchant.type).toString())
            put("result", offer.result.json(player.level()) ?: JsonPrimitive(null as String?))
            put("uses", offer.uses)
        }))
    }

    fun broke(player: ServerPlayer, state: BlockState) = windows.block(Topic.BLOCK_BREAK, player.ref(), player.level().worldName, state.typeKey)

    fun placed(player: ServerPlayer, state: BlockState) = windows.block(Topic.BLOCK_PLACE, player.ref(), player.level().worldName, state.typeKey)

    fun nightSkipped(level: ServerLevel, skipped: Long, sleepers: List<ServerPlayer>) {
        publish(ApiEvent(Topic.WORLD_NIGHT_SKIPPED, buildJsonObject {
            put("skippedTicks", skipped)
            put("day", level.defaultClockTime / 24_000 + 1)
            put("sleepers", JsonArray(sleepers.filterNot(loader::vanished).map { JsonPrimitive(it.gameProfile.name()) }))
        }, world = level.worldName))
    }

    fun lightning(level: ServerLevel, pos: Vec3, effectOnly: Boolean) {
        publish(ApiEvent(Topic.WORLD_LIGHTNING, buildJsonObject {
            put("cause", "unknown")
            put("effectOnly", effectOnly)
            put("location", locationJson(level, pos))
        }, world = level.worldName))
    }

    fun exploded(level: ServerLevel, source: Entity?, pos: Vec3, blocks: Int) {
        publish(ApiEvent(Topic.ENTITY_EXPLODE, buildJsonObject {
            put("entity", source?.let { BuiltInRegistries.ENTITY_TYPE.getKey(it.type).toString() })
            if (source == null) put("block", level.getBlockState(BlockPos.containing(pos)).typeKey)
            put("blocks", blocks)
            put("location", locationJson(level, pos))
        }, world = level.worldName))
    }

    fun raidStarted(level: ServerLevel, player: ServerPlayer?, omen: Int, waves: Int, center: BlockPos) {
        publish(ApiEvent(Topic.RAID_START, buildJsonObject {
            put("badOmenLevel", omen)
            put("waves", waves)
            put("location", locationJson(level, Vec3.atBottomCenterOf(center)))
        }, player?.ref(), level.worldName))
    }

    /**
     * A raid that ended: `victory` with its winners, or how it stopped otherwise. A lost raid sends nothing, as on Paper,
     * where it ends as a stop that's merely finished.
     */
    fun raidFinished(level: ServerLevel, result: String, winners: List<String>, center: BlockPos) {
        publish(ApiEvent(Topic.RAID_FINISH, buildJsonObject {
            put("result", result)
            if (result == "victory") put("winners", JsonArray(winners.map(::JsonPrimitive)))
            put("location", locationJson(level, Vec3.atBottomCenterOf(center)))
        }, world = level.worldName))
    }

    fun tamed(owner: Player) {
        val player = owner as? ServerPlayer ?: return
        tamed.add(player.uuid, player.gameProfile.name())
    }

    fun saved() {
        // One save writes every world; tell clients once.
        val now = System.currentTimeMillis()
        if (now - lastSave < 5_000) return
        lastSave = now
        if (bus.interest(Topic.SERVER_STATUS)) bus.stream(ApiEvent(Topic.SERVER_STATUS, buildJsonObject { put("status", "saved") }))
    }
}
