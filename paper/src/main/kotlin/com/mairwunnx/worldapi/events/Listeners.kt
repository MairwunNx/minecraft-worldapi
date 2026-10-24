package com.mairwunnx.worldapi.events

import com.mairwunnx.worldapi.events.Vanished
import com.mairwunnx.worldapi.bukkit.check
import com.mairwunnx.worldapi.bukkit.isVanished
import com.mairwunnx.worldapi.bukkit.plain
import com.mairwunnx.worldapi.bukkit.uuidOrNull
import com.mairwunnx.worldapi.bukkit.ref
import com.mairwunnx.worldapi.routes.weather
import com.mairwunnx.worldapi.routes.worldTime
import com.mairwunnx.worldapi.stats.Experience
import com.mairwunnx.worldapi.store.History
import io.papermc.paper.event.player.AsyncChatEvent
import io.papermc.paper.event.player.PlayerTradeEvent
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.bukkit.entity.ElderGuardian
import org.bukkit.entity.EnderDragon
import org.bukkit.entity.Player
import org.bukkit.entity.Warden
import org.bukkit.entity.Wither
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.enchantment.EnchantItemEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.inventory.CraftItemEvent
import org.bukkit.event.player.PlayerAdvancementDoneEvent
import org.bukkit.event.player.PlayerBedEnterEvent
import org.bukkit.event.player.PlayerBedLeaveEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerExpChangeEvent
import org.bukkit.event.player.PlayerFishEvent
import org.bukkit.event.player.PlayerGameModeChangeEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerKickEvent
import org.bukkit.event.player.PlayerLevelChangeEvent
import org.bukkit.event.player.PlayerPortalEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.event.raid.RaidFinishEvent
import org.bukkit.event.raid.RaidStopEvent
import org.bukkit.event.raid.RaidTriggerEvent
import org.bukkit.event.server.PluginDisableEvent
import org.bukkit.event.server.PluginEnableEvent
import org.bukkit.event.weather.LightningStrikeEvent
import org.bukkit.event.weather.ThunderChangeEvent
import org.bukkit.event.weather.WeatherChangeEvent
import org.bukkit.event.world.ClockTimeSkipEvent
import org.bukkit.event.world.WorldLoadEvent
import org.bukkit.event.world.WorldSaveEvent
import org.bukkit.event.world.WorldUnloadEvent
import org.bukkit.plugin.Plugin
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Turns Bukkit events into WorldAPI events. Every handler runs after other plugins had their say. */
class Listeners(
    private val plugin: Plugin,
    private val bus: EventBus,
    private val history: History,
    private val experience: Experience,
    private val windows: Windows,
    private val vanished: Vanished,
    private val chatEnabled: () -> Boolean,
) : Listener {
    private val server = plugin.server
    private val joinedAt = ConcurrentHashMap<UUID, Long>()
    private val lastDamage = ConcurrentHashMap<UUID, Long>()
    private var lastSave = 0L

    private fun publish(event: ApiEvent) {
        if (!bus.wanted(event.topic)) return
        // Vanish plugins share no event: look at the player now, while on the server thread. Chat arrives off it.
        if (server.isPrimaryThread) event.player?.uuid?.let(::uuidOrNull)?.let(server::getPlayer)?.let { vanished.check(it) }
        bus.publish(event)
    }

    /** Counts in event payloads leave vanished players out, since everyone may read them. */
    private fun visible(players: Collection<Player>) = players.count { !it.isVanished() }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        joinedAt[player.uniqueId] = System.currentTimeMillis()
        history.openSession(player.uniqueId.toString(), player.name, Instant.now(), hidden = player.isVanished())
        // Someone who never joined since WorldAPI started counting gets their baseline now.
        experience.seed(player.uniqueId, player.name, player.totalExperience)
        publish(player.event(Topic.PLAYER_JOIN, buildJsonObject {
            put("firstJoin", !player.hasPlayedBefore())
            put("online", visible(server.onlinePlayers))
        }))
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        val player = event.player
        history.closeSession(player.uniqueId.toString(), Instant.now())
        val since = joinedAt.remove(player.uniqueId)
        publish(player.event(Topic.PLAYER_LEAVE, buildJsonObject {
            put("reason", event.reason.name.lowercase())
            since?.let { put("sessionSeconds", (System.currentTimeMillis() - it) / 1000) }
            put("online", visible(server.onlinePlayers.filter { it != player }))
        }))
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onKick(event: PlayerKickEvent) {
        publish(event.player.event(Topic.PLAYER_KICK, buildJsonObject {
            put("reason", event.reason().plain())
            put("cause", event.cause.name.lowercase())
        }))
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDeath(event: PlayerDeathEvent) {
        val player = event.player
        val damage = event.damageSource
        publish(player.event(Topic.PLAYER_DEATH, buildJsonObject {
            put("message", event.deathMessage()?.plain() ?: "${player.name} died")
            put("cause", damage.damageType.key.asString())
            (damage.causingEntity ?: player.killer)?.let { killer ->
                putJsonObject("killer") {
                    put("type", killer.type.key.asString())
                    put("name", (killer as? Player)?.name ?: killer.customName()?.plain())
                    put("uuid", killer.uniqueId.toString())
                }
            }
            put("level", player.level)
            put("keepInventory", event.keepInventory)
            put("drops", event.drops.sumOf { it.amount })
            put("location", player.location.json())
        }))
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onRespawn(event: PlayerRespawnEvent) {
        publish(event.player.event(Topic.PLAYER_RESPAWN, buildJsonObject {
            put("reason", event.respawnReason.name.lowercase())
            put("bed", event.isBedSpawn)
            put("anchor", event.isAnchorSpawn)
            put("location", event.respawnLocation.json())
        }))
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onAdvancement(event: PlayerAdvancementDoneEvent) {
        val advancement = event.advancement
        // Recipes and hidden technical advancements have no display; vanilla doesn't announce them either.
        val display = advancement.display ?: return
        publish(event.player.event(Topic.PLAYER_ADVANCEMENT, buildJsonObject {
            put("key", advancement.key.asString())
            put("title", display.title().plain())
            put("description", display.description().plain())
            put("frame", display.frame().name.lowercase())
            put("announced", display.doesAnnounceToChat())
        }))
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onChat(event: AsyncChatEvent) {
        if (!chatEnabled()) return
        val player = event.player
        // Runs off the server thread; the bus and the store are safe to call from here.
        publish(ApiEvent(Topic.PLAYER_CHAT, buildJsonObject { put("message", event.message().plain()) }, player.ref(), player.world.name, player.uniqueId.toString()))
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onCommand(event: PlayerCommandPreprocessEvent) {
        if (!bus.wanted(Topic.PLAYER_COMMAND)) return
        publish(event.player.event(Topic.PLAYER_COMMAND, buildJsonObject { put("command", event.message.removePrefix("/")) }))
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onWorldChange(event: PlayerChangedWorldEvent) {
        publish(event.player.event(Topic.PLAYER_WORLD_CHANGE, buildJsonObject {
            put("from", event.from.name)
            put("to", event.player.world.name)
        }))
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPortal(event: PlayerPortalEvent) {
        publish(event.player.event(Topic.PLAYER_PORTAL, buildJsonObject {
            put("cause", event.cause.name.lowercase())
            put("from", event.from.json())
            put("to", event.to.json())
        }))
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDamage(event: EntityDamageEvent) {
        val player = event.entity as? Player ?: return
        if (!bus.wanted(Topic.PLAYER_DAMAGE)) return
        // At most four per second per player: a lava bath shouldn't flood clients.
        val now = System.currentTimeMillis()
        if (now - (lastDamage[player.uniqueId] ?: 0) < 250) return
        lastDamage[player.uniqueId] = now
        publish(player.event(Topic.PLAYER_DAMAGE, buildJsonObject {
            put("cause", event.damageSource.damageType.key.asString())
            put("damage", event.finalDamage)
            put("health", (player.health - event.finalDamage).coerceAtLeast(0.0))
            (event as? EntityDamageByEntityEvent)?.damager?.let { put("damager", it.type.key.asString()) }
        }))
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onEntityDeath(event: EntityDeathEvent) {
        val entity = event.entity
        val killer = entity.killer
        if (entity !is Player) windows.entityDeath(entity.world.name, entity.type.key.asString())
        if (killer != null && entity !is Player) {
            publish(killer.event(Topic.PLAYER_KILL, buildJsonObject {
                put("victim", entity.type.key.asString())
                entity.customName()?.let { put("victimName", it.plain()) }
                put("weapon", killer.inventory.itemInMainHand.type.key.asString())
                put("location", entity.location.json())
            }))
        }
        if (entity is EnderDragon || entity is Wither || entity is ElderGuardian || entity is Warden) {
            val participants = entity.world.getNearbyPlayers(entity.location, 128.0).map { it.name }.sorted()
            publish(ApiEvent(Topic.ENTITY_BOSS_KILLED, buildJsonObject {
                put("boss", entity.type.key.asString())
                put("killer", killer?.name)
                put("players", kotlinx.serialization.json.JsonArray(participants.map(::JsonPrimitive)))
                put("location", entity.location.json())
            }, killer?.ref(), entity.world.name))
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onGameMode(event: PlayerGameModeChangeEvent) {
        if (event.isCancelled) return
        publish(event.player.event(Topic.PLAYER_GAMEMODE, buildJsonObject {
            put("from", event.player.gameMode.name.lowercase())
            put("to", event.newGameMode.name.lowercase())
        }))
    }

    /** Orbs and other rewards, after other plugins changed the amount; `/xp` doesn't fire this. */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onExperience(event: PlayerExpChangeEvent) = experience.add(event.player.uniqueId, event.player.name, event.amount)

    @EventHandler(priority = EventPriority.MONITOR)
    fun onLevel(event: PlayerLevelChangeEvent) {
        publish(event.player.event(Topic.PLAYER_LEVEL_UP, buildJsonObject {
            put("from", event.oldLevel)
            put("to", event.newLevel)
        }))
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBedEnter(event: PlayerBedEnterEvent) {
        if (event.bedEnterResult != PlayerBedEnterEvent.BedEnterResult.OK) return
        sleep(event.player, entering = true)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onBedLeave(event: PlayerBedLeaveEvent) = sleep(event.player, entering = false)

    private fun sleep(player: Player, entering: Boolean) {
        val world = player.world
        val sleeping = world.players.count { it.isSleeping || (entering && it == player) } - if (!entering && player.isSleeping) 1 else 0
        publish(player.event(Topic.PLAYER_SLEEP, buildJsonObject {
            put("action", if (entering) "enter" else "leave")
            put("sleeping", sleeping.coerceAtLeast(0))
            put("players", world.players.count { !it.isSleepingIgnored && !it.isVanished() })
        }))
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onFish(event: PlayerFishEvent) {
        if (event.state != PlayerFishEvent.State.CAUGHT_FISH) return
        val item = (event.caught as? org.bukkit.entity.Item)?.itemStack
        publish(event.player.event(Topic.PLAYER_FISH, buildJsonObject {
            put("item", item.json() ?: JsonPrimitive(null as String?))
        }))
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onCraft(event: CraftItemEvent) {
        val player = event.whoClicked as? Player ?: return
        windows.craft(player, event.recipe.result.type.key.asString(), event.recipe.result.amount)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEnchant(event: EnchantItemEvent) {
        publish(event.enchanter.event(Topic.PLAYER_ENCHANT, buildJsonObject {
            put("item", event.item.type.key.asString())
            put("levelCost", event.expLevelCost)
            putJsonObject("enchantments") { event.enchantsToAdd.forEach { (enchantment, level) -> put(enchantment.key.asString(), level) } }
        }))
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onTrade(event: PlayerTradeEvent) {
        publish(event.player.event(Topic.PLAYER_TRADE, buildJsonObject {
            put("merchant", event.villager.type.key.asString())
            put("result", event.trade.result.json() ?: JsonPrimitive(null as String?))
            put("uses", event.trade.uses)
        }))
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBreak(event: BlockBreakEvent) = windows.block(Topic.BLOCK_BREAK, event.player, event.block.type.key.asString())

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlace(event: BlockPlaceEvent) = windows.block(Topic.BLOCK_PLACE, event.player, event.block.type.key.asString())

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onNightSkip(event: ClockTimeSkipEvent) {
        if (event.skipReason != ClockTimeSkipEvent.SkipReason.NIGHT_SKIP) return
        val world = server.worlds.first()
        val skipped = event.skipAmount
        publish(ApiEvent(Topic.WORLD_NIGHT_SKIPPED, buildJsonObject {
            put("skippedTicks", skipped)
            put("day", (world.fullTime + skipped) / 24_000 + 1)
            put("sleepers", kotlinx.serialization.json.JsonArray(world.players.filter { it.isSleeping && !it.isVanished() }.map { JsonPrimitive(it.name) }))
        }, world = world.name))
        // The clock jumps after this event; send the new time once it has.
        server.scheduler.runTask(plugin, Runnable { publishTime(world) })
    }

    fun publishTime(world: org.bukkit.World) {
        if (bus.wanted(Topic.WORLD_TIME)) bus.publish(ApiEvent(Topic.WORLD_TIME, com.mairwunnx.worldapi.model.WorldTime.serializer().json(worldTime(world)), world = world.name, subject = world.name))
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onWeather(event: WeatherChangeEvent) = publishWeatherLater(event.world)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onThunder(event: ThunderChangeEvent) = publishWeatherLater(event.world)

    /** Weather events fire before the change lands, so read the new state a tick later. */
    private fun publishWeatherLater(world: org.bukkit.World) {
        if (!bus.wanted(Topic.WORLD_WEATHER)) return
        server.scheduler.runTask(plugin, Runnable {
            bus.publish(ApiEvent(Topic.WORLD_WEATHER, com.mairwunnx.worldapi.model.Weather.serializer().json(weather(world)), world = world.name, subject = world.name))
        })
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onLightning(event: LightningStrikeEvent) {
        publish(ApiEvent(Topic.WORLD_LIGHTNING, buildJsonObject {
            put("cause", event.cause.name.lowercase())
            put("effectOnly", event.lightning.isEffect)
            put("location", event.lightning.location.json())
        }, world = event.world.name))
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onWorldLoad(event: WorldLoadEvent) = publish(ApiEvent(Topic.WORLD_LOAD, buildJsonObject { put("environment", event.world.environment.name.lowercase()) }, world = event.world.name))

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onWorldUnload(event: WorldUnloadEvent) = publish(ApiEvent(Topic.WORLD_UNLOAD, buildJsonObject {}, world = event.world.name))

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onExplode(event: EntityExplodeEvent) {
        publish(ApiEvent(Topic.ENTITY_EXPLODE, buildJsonObject {
            put("entity", event.entityType.key.asString())
            put("blocks", event.blockList().size)
            put("location", event.location.json())
        }, world = event.entity.world.name))
    }

    /** Explosions without an entity behind them: beds in the nether, respawn anchors, and the API's own. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockExplode(event: org.bukkit.event.block.BlockExplodeEvent) {
        publish(ApiEvent(Topic.ENTITY_EXPLODE, buildJsonObject {
            put("entity", null as String?)
            put("block", event.explodedBlockState.type.key.asString())
            put("blocks", event.blockList().size)
            put("location", event.block.location.json())
        }, world = event.block.world.name))
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onRaidStart(event: RaidTriggerEvent) {
        publish(ApiEvent(Topic.RAID_START, buildJsonObject {
            put("badOmenLevel", event.raid.badOmenLevel)
            put("waves", event.raid.totalWaves)
            put("location", event.raid.location.json())
        }, event.player.ref(), event.world.name))
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onRaidFinish(event: RaidFinishEvent) {
        publish(ApiEvent(Topic.RAID_FINISH, buildJsonObject {
            put("result", "victory")
            put("winners", kotlinx.serialization.json.JsonArray(event.winners.map { JsonPrimitive(it.name) }))
            put("location", event.raid.location.json())
        }, world = event.world.name))
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onRaidStop(event: RaidStopEvent) {
        if (event.reason == RaidStopEvent.Reason.FINISHED) return
        publish(ApiEvent(Topic.RAID_FINISH, buildJsonObject {
            put("result", event.reason.name.lowercase())
            put("location", event.raid.location.json())
        }, world = event.world.name))
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onSave(event: WorldSaveEvent) {
        // One save fires this once per world; tell clients once.
        val now = System.currentTimeMillis()
        if (now - lastSave < 5_000) return
        lastSave = now
        if (bus.interest(Topic.SERVER_STATUS)) bus.stream(ApiEvent(Topic.SERVER_STATUS, buildJsonObject { put("status", "saved") }))
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onPluginEnable(event: PluginEnableEvent) = plugin(event.plugin, true)

    @EventHandler(priority = EventPriority.MONITOR)
    fun onPluginDisable(event: PluginDisableEvent) = plugin(event.plugin, false)

    private fun plugin(other: Plugin, enabled: Boolean) {
        if (other == plugin) return
        publish(ApiEvent(Topic.SERVER_PLUGIN, buildJsonObject {
            put("plugin", other.name)
            put("version", other.pluginMeta.version)
            put("enabled", enabled)
        }))
    }
}
