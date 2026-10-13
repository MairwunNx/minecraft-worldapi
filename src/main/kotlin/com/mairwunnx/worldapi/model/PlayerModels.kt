package com.mairwunnx.worldapi.model

import kotlinx.serialization.Serializable

@Serializable
data class PlayerSummary(
    val uuid: String,
    val name: String,
    val online: Boolean,
    val firstPlayed: String? = null,
    val lastSeen: String? = null,
    /** Live for online players, from the stats file otherwise. */
    val playtimeSeconds: Long,
)

@Serializable
data class OnlinePlayer(
    val uuid: String,
    val name: String,
    val displayName: String,
    val world: String,
    val gameMode: String,
    val health: Double,
    val food: Int,
    val level: Int,
    val ping: Int,
    val idleSeconds: Long,
    val location: LocationView? = null,
)

@Serializable
data class OnlinePlayers(val count: Int, val max: Int, val players: List<OnlinePlayer>)

@Serializable
data class PlayerView(
    val uuid: String,
    val name: String,
    val online: Boolean,
    val firstPlayed: String? = null,
    val lastSeen: String? = null,
    val skinUrl: String? = null,
    val op: Boolean,
    val whitelisted: Boolean,
    val banned: Boolean,
    /** Last known position; for offline players, where they logged out. */
    val location: LocationView? = null,
    val respawnLocation: LocationView? = null,
    /** Present while the player is online. */
    val state: PlayerState? = null,
)

@Serializable
data class PlayerState(
    val displayName: String,
    val listName: String,
    val gameMode: String,
    val health: Double,
    val maxHealth: Double,
    val absorption: Double,
    val food: Int,
    val saturation: Float,
    val exhaustion: Float,
    val level: Int,
    /** Progress to the next level, 0–1. */
    val exp: Float,
    val totalExperience: Int,
    val allowFlight: Boolean,
    val flying: Boolean,
    val walkSpeed: Float,
    val flySpeed: Float,
    val invulnerable: Boolean,
    val fireTicks: Int,
    val remainingAir: Int,
    val maximumAir: Int,
    val sleeping: Boolean,
    val sneaking: Boolean,
    val sprinting: Boolean,
    val ping: Int,
    val clientBrand: String? = null,
    val locale: String,
    val idleSeconds: Long,
    /** Only for tokens with the admin scope. */
    val address: String? = null,
)

@Serializable
data class PlayerPatch(
    /** `survival`, `creative`, `adventure`, or `spectator`. */
    val gameMode: String? = null,
    val health: Double? = null,
    val food: Int? = null,
    val saturation: Float? = null,
    val exhaustion: Float? = null,
    val level: Int? = null,
    val exp: Float? = null,
    val allowFlight: Boolean? = null,
    val flying: Boolean? = null,
    /** -1 to 1; the default is 0.2. */
    val walkSpeed: Float? = null,
    /** -1 to 1; the default is 0.1. */
    val flySpeed: Float? = null,
    val displayName: String? = null,
    val listName: String? = null,
    val format: TextFormat = TextFormat.PLAIN,
    val invulnerable: Boolean? = null,
    val fireTicks: Int? = null,
    val remainingAir: Int? = null,
)

/** Exactly one of location, player, or worldSpawn. */
@Serializable
data class TeleportRequest(
    val location: LocationView? = null,
    /** Another player's name or UUID. */
    val player: String? = null,
    /** A world name; the player lands on its spawn. */
    val worldSpawn: String? = null,
)

@Serializable
data class TeleportResult(val success: Boolean, val location: LocationView)

@Serializable
data class MessageRequest(
    val message: String,
    val kind: MessageKind = MessageKind.CHAT,
    val format: TextFormat = TextFormat.PLAIN,
    val subtitle: String? = null,
    val fadeInMillis: Long? = null,
    val stayMillis: Long? = null,
    val fadeOutMillis: Long? = null,
    /** Play a sound to the player too, such as `minecraft:entity.experience_orb.pickup`. */
    val sound: String? = null,
)

@Serializable
data class KickRequest(val reason: String? = null, val format: TextFormat = TextFormat.PLAIN)

@Serializable
data class PermissionView(
    val op: Boolean,
    /** The node asked about, and whether the player has it. Online players only. */
    val node: String? = null,
    val has: Boolean? = null,
    /** Permissions set explicitly for an online player, by plugins or ops. */
    val effective: Map<String, Boolean>? = null,
)

@Serializable
data class PermissionPatch(val op: Boolean)

@Serializable
data class PlayerStatistics(
    val player: PlayerRef,
    /** True when the numbers come from the live server; false when read from the last saved file. */
    val live: Boolean,
    /** Category, such as `minecraft:mined`, then key, then value, as vanilla stores them. */
    val stats: Map<String, Map<String, Long>>,
)

@Serializable
data class AdvancementView(
    val key: String,
    val title: String? = null,
    val description: String? = null,
    /** `task`, `goal`, or `challenge`. */
    val frame: String? = null,
    val done: Boolean,
    /** When the last criterion was met. */
    val doneAt: String? = null,
    val criteriaDone: Int,
    val criteriaTotal: Int,
    /** The advancement before this one in the tree, such as `minecraft:story/root`; absent for a tab's root. */
    val parent: String? = null,
)

@Serializable
data class Advancements(val player: PlayerRef, val done: Int, val advancements: List<AdvancementView>)
