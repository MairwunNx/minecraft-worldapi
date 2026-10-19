package com.mairwunnx.worldapi.events

import com.mairwunnx.worldapi.api.Scope
import java.util.concurrent.ConcurrentHashMap

/**
 * Everything WorldAPI streams. A state topic carries the latest value of something (time,
 * health, position): a slow client only needs the newest one, so those coalesce per subject.
 * An event topic is a fact that happened once; those queue up and drop oldest-first.
 * Recorded topics also go to the event history. WorldAPI's own topics are fixed; other plugins add
 * theirs at runtime through the extension bridge, as `ext.<namespace>.<event>`.
 */
class Topic private constructor(
    val id: String,
    val state: Boolean = false,
    val recorded: Boolean = false,
    val privacy: Privacy = Privacy.NONE,
    val scope: Scope = Scope.EVENTS_READ,
    /** For a plugin's topic: its namespace, and whether the plugin lets anonymous clients see it. */
    val extension: Extension? = null,
) {
    class Extension(val namespace: String, val public: Boolean, val description: String)

    override fun toString() = id

    /**
     * What an anonymous client needs to see this topic. COORDINATES strips location fields;
     * COORDINATES_ONLY means the topic is nothing but a location, so it is hidden entirely.
     */
    enum class Privacy { NONE, COORDINATES, COORDINATES_ONLY, CHAT, INVENTORIES }

    companion object {
        private val builtIns = ArrayList<Topic>()
        private val extensions = ConcurrentHashMap<String, Topic>()

        private fun builtIn(
            id: String, state: Boolean = false, recorded: Boolean = false, privacy: Privacy = Privacy.NONE, scope: Scope = Scope.EVENTS_READ,
        ) = Topic(id, state, recorded, privacy, scope).also { builtIns += it }

        val PLAYER_JOIN = builtIn("player.join", recorded = true)
        val PLAYER_LEAVE = builtIn("player.leave", recorded = true)
        val PLAYER_KICK = builtIn("player.kick", recorded = true)
        val PLAYER_DEATH = builtIn("player.death", recorded = true, privacy = Privacy.COORDINATES)
        val PLAYER_RESPAWN = builtIn("player.respawn", privacy = Privacy.COORDINATES)
        val PLAYER_ADVANCEMENT = builtIn("player.advancement", recorded = true)
        val PLAYER_CHAT = builtIn("player.chat", recorded = true, privacy = Privacy.CHAT, scope = Scope.CHAT_READ)
        val PLAYER_COMMAND = builtIn("player.command", scope = Scope.LOGS_READ)
        val PLAYER_MOVE = builtIn("player.move", state = true, privacy = Privacy.COORDINATES_ONLY)
        val PLAYER_WORLD_CHANGE = builtIn("player.world_change")
        val PLAYER_PORTAL = builtIn("player.portal", privacy = Privacy.COORDINATES)
        val PLAYER_HEALTH = builtIn("player.health", state = true)
        val PLAYER_DAMAGE = builtIn("player.damage")
        val PLAYER_KILL = builtIn("player.kill", privacy = Privacy.COORDINATES)
        val PLAYER_INVENTORY = builtIn("player.inventory", state = true, privacy = Privacy.INVENTORIES)
        val PLAYER_GAMEMODE = builtIn("player.gamemode")
        val PLAYER_LEVEL_UP = builtIn("player.level_up")
        val PLAYER_SLEEP = builtIn("player.sleep")
        val PLAYER_FISH = builtIn("player.fish")
        val PLAYER_CRAFT = builtIn("player.craft")
        val PLAYER_ENCHANT = builtIn("player.enchant")
        val PLAYER_TRADE = builtIn("player.trade")
        val WORLD_TIME = builtIn("world.time", state = true)
        val WORLD_WEATHER = builtIn("world.weather", state = true)
        val WORLD_NIGHT_SKIPPED = builtIn("world.night_skipped", recorded = true)
        val WORLD_LIGHTNING = builtIn("world.lightning", privacy = Privacy.COORDINATES)
        val WORLD_LOAD = builtIn("world.load")
        val WORLD_UNLOAD = builtIn("world.unload")
        val ENTITY_BOSS_KILLED = builtIn("entity.boss_killed", recorded = true, privacy = Privacy.COORDINATES)
        val ENTITY_DEATH = builtIn("entity.death")
        val ENTITY_EXPLODE = builtIn("entity.explode", privacy = Privacy.COORDINATES)
        val RAID_START = builtIn("raid.start", recorded = true, privacy = Privacy.COORDINATES)
        val RAID_FINISH = builtIn("raid.finish", recorded = true, privacy = Privacy.COORDINATES)
        val BLOCK_BREAK = builtIn("block.break")
        val BLOCK_PLACE = builtIn("block.place")
        val SERVER_PERFORMANCE = builtIn("server.performance", state = true)
        val SERVER_STATUS = builtIn("server.status", recorded = true)
        val SERVER_PLUGIN = builtIn("server.plugin")
        val SERVER_CONSOLE = builtIn("server.console", scope = Scope.LOGS_READ)
        val SCRIPT_LOG = builtIn("script.log", scope = Scope.CODE)

        private val byId by lazy { builtIns.associateBy { it.id } }

        /** WorldAPI's own topics, then the plugins' by id. */
        val entries: List<Topic> get() = builtIns + extensions.values.sortedBy { it.id }

        fun of(id: String): Topic? = byId[id] ?: extensions[id]

        /**
         * A plugin's event topic. Its data may carry coordinates, so anonymous clients get it without
         * them, as with WorldAPI's own topics marked COORDINATES.
         */
        fun extension(namespace: String, event: String, recorded: Boolean, public: Boolean, description: String): Topic =
            Topic("ext.$namespace.$event", recorded = recorded, privacy = Privacy.COORDINATES, extension = Extension(namespace, public, description))
                .also { extensions[it.id] = it }

        /** Drops a namespace's topics, when its plugin stops. */
        fun forget(namespace: String) = extensions.values.removeIf { it.extension?.namespace == namespace }

        /** Data keys that hold coordinates; anonymous clients don't get them unless privacy allows. */
        val locationKeys = setOf("location", "position", "spawn", "from", "to", "center")
    }
}
