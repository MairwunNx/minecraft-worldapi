package com.mairwunnx.worldapi.events

import com.mairwunnx.worldapi.api.Scope

/**
 * Everything WorldAPI streams. A state topic carries the latest value of something (time,
 * health, position): a slow client only needs the newest one, so those coalesce per subject.
 * An event topic is a fact that happened once; those queue up and drop oldest-first.
 * Recorded topics also go to the event history.
 */
enum class Topic(
    val id: String,
    val state: Boolean = false,
    val recorded: Boolean = false,
    val privacy: Privacy = Privacy.NONE,
    val scope: Scope = Scope.READ,
) {
    PLAYER_JOIN("player.join", recorded = true),
    PLAYER_LEAVE("player.leave", recorded = true),
    PLAYER_KICK("player.kick", recorded = true),
    PLAYER_DEATH("player.death", recorded = true, privacy = Privacy.COORDINATES),
    PLAYER_RESPAWN("player.respawn", privacy = Privacy.COORDINATES),
    PLAYER_ADVANCEMENT("player.advancement", recorded = true),
    PLAYER_CHAT("player.chat", recorded = true, privacy = Privacy.CHAT),
    PLAYER_COMMAND("player.command", scope = Scope.ADMIN),
    PLAYER_MOVE("player.move", state = true, privacy = Privacy.COORDINATES_ONLY),
    PLAYER_WORLD_CHANGE("player.world_change"),
    PLAYER_PORTAL("player.portal", privacy = Privacy.COORDINATES),
    PLAYER_HEALTH("player.health", state = true),
    PLAYER_DAMAGE("player.damage"),
    PLAYER_KILL("player.kill", privacy = Privacy.COORDINATES),
    PLAYER_INVENTORY("player.inventory", state = true, privacy = Privacy.INVENTORIES),
    PLAYER_GAMEMODE("player.gamemode"),
    PLAYER_LEVEL_UP("player.level_up"),
    PLAYER_SLEEP("player.sleep"),
    PLAYER_FISH("player.fish"),
    PLAYER_CRAFT("player.craft"),
    PLAYER_ENCHANT("player.enchant"),
    PLAYER_TRADE("player.trade"),
    WORLD_TIME("world.time", state = true),
    WORLD_WEATHER("world.weather", state = true),
    WORLD_NIGHT_SKIPPED("world.night_skipped", recorded = true),
    WORLD_LIGHTNING("world.lightning", privacy = Privacy.COORDINATES),
    WORLD_LOAD("world.load"),
    WORLD_UNLOAD("world.unload"),
    ENTITY_BOSS_KILLED("entity.boss_killed", recorded = true, privacy = Privacy.COORDINATES),
    ENTITY_DEATH("entity.death"),
    ENTITY_EXPLODE("entity.explode", privacy = Privacy.COORDINATES),
    RAID_START("raid.start", recorded = true, privacy = Privacy.COORDINATES),
    RAID_FINISH("raid.finish", recorded = true, privacy = Privacy.COORDINATES),
    BLOCK_BREAK("block.break"),
    BLOCK_PLACE("block.place"),
    SERVER_PERFORMANCE("server.performance", state = true),
    SERVER_STATUS("server.status", recorded = true),
    SERVER_PLUGIN("server.plugin"),
    SCRIPT_LOG("script.log", scope = Scope.CODE),
    ;

    /**
     * What an anonymous client needs to see this topic. COORDINATES strips location fields;
     * COORDINATES_ONLY means the topic is nothing but a location, so it is hidden entirely.
     */
    enum class Privacy { NONE, COORDINATES, COORDINATES_ONLY, CHAT, INVENTORIES }

    companion object {
        private val byId = entries.associateBy { it.id }
        fun of(id: String): Topic? = byId[id]

        /** Data keys that hold coordinates; anonymous clients don't get them unless privacy allows. */
        val locationKeys = setOf("location", "position", "spawn", "from", "to", "center")
    }
}
