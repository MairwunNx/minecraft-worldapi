package com.mairwunnx.worldapi.model

import kotlinx.serialization.Serializable

@Serializable
data class ServerInfo(
    val name: String,
    /** Server software, such as Paper. */
    val brand: String,
    val minecraftVersion: String,
    val build: Int? = null,
    val buildTime: String? = null,
    val apiVersion: String,
    val worldApiVersion: String,
    val motd: String,
    val maxPlayers: Int,
    val onlinePlayers: Int,
    val startedAt: String,
    val uptimeSeconds: Long,
    val defaultGameMode: String,
    val difficulty: String,
    val hardcore: Boolean,
    val onlineMode: Boolean,
    val whitelist: Boolean,
    val viewDistance: Int,
    val simulationDistance: Int,
    val worlds: List<String>,
    val currentTick: Int,
    val tickRate: Float,
    val frozen: Boolean,
    /** Ticks per second over the last minute, at most 20. `/server/performance` has more detail. */
    val tps: Double,
    val tps5m: Double,
    val tps15m: Double,
    /** Milliseconds per tick over the last 100 ticks. */
    val mspt: Double,
    /** Java heap in use, in MiB. */
    val memoryUsedMb: Long,
    val memoryMaxMb: Long,
    /** Across every world. */
    val loadedChunks: Int,
    val entities: Int,
)

@Serializable
data class Performance(
    val tps: Tps,
    /** Milliseconds per tick over the last 100 ticks. */
    val mspt: Mspt,
    val memory: Memory,
    val cpu: Cpu,
    val worlds: List<WorldLoad>,
    val at: String,
)

@Serializable
data class Tps(val oneMinute: Double, val fiveMinutes: Double, val fifteenMinutes: Double)

@Serializable
data class Mspt(val average: Double, val p50: Double, val p95: Double, val p99: Double, val max: Double)

@Serializable
data class Memory(val usedBytes: Long, val committedBytes: Long, val maxBytes: Long)

@Serializable
data class Cpu(
    /** Share of all cores this process uses, 0 to 1; null when the JVM can't tell. */
    val process: Double? = null,
    val system: Double? = null,
    val cores: Int,
)

@Serializable
data class WorldLoad(
    val world: String,
    val loadedChunks: Int,
    val entities: Int,
    val tileEntities: Int,
    val players: Int,
    /** The most common entity types and how many are loaded, most first. Only `GET /server/performance` sends it, not the stream. */
    val entityTypes: Map<String, Int>? = null,
)

@Serializable
data class PluginInfo(
    val name: String,
    val version: String,
    val enabled: Boolean,
    val description: String? = null,
    val authors: List<String> = emptyList(),
    val website: String? = null,
)

@Serializable
enum class MessageKind {
    @kotlinx.serialization.SerialName("chat") CHAT,
    @kotlinx.serialization.SerialName("title") TITLE,
    @kotlinx.serialization.SerialName("actionbar") ACTIONBAR,
}

@Serializable
data class BroadcastRequest(
    val message: String,
    val kind: MessageKind = MessageKind.CHAT,
    val format: TextFormat = TextFormat.PLAIN,
    /** Only for titles. */
    val subtitle: String? = null,
    val fadeInMillis: Long? = null,
    val stayMillis: Long? = null,
    val fadeOutMillis: Long? = null,
)

@Serializable
data class Delivered(val recipients: Int)

@Serializable
data class CommandRequest(
    /** A console command without the leading slash, such as `time set day`. */
    val command: String,
)

@Serializable
data class CommandResult(val success: Boolean, val output: List<String>)

@Serializable
data class SaveRequest(
    /** Also write player data to disk. */
    val players: Boolean = true,
)

@Serializable
data class SaveResult(val worlds: List<String>, val players: Boolean)

@Serializable
data class Whitelist(val enabled: Boolean, val enforced: Boolean, val players: List<PlayerRef>)

@Serializable
data class WhitelistPatch(
    val enabled: Boolean? = null,
    val enforced: Boolean? = null,
    /** Names or UUIDs to add. Names that never joined are looked up with Mojang. */
    val add: List<String> = emptyList(),
    val remove: List<String> = emptyList(),
)

@Serializable
data class Operators(val players: List<PlayerRef>)

@Serializable
data class OperatorsPatch(val add: List<String> = emptyList(), val remove: List<String> = emptyList())

@Serializable
data class Bans(val players: List<BanView>, val ips: List<BanView>)

@Serializable
data class BanView(
    /** A player name or an IP address. */
    val target: String,
    val uuid: String? = null,
    val reason: String? = null,
    val source: String? = null,
    val created: String? = null,
    /** Absent for a permanent ban. */
    val expires: String? = null,
)

@Serializable
data class BansPatch(
    val ban: List<BanRequest> = emptyList(),
    /** Player names or UUIDs to pardon. */
    val pardon: List<String> = emptyList(),
    val banIps: List<IpBanRequest> = emptyList(),
    val pardonIps: List<String> = emptyList(),
)

@Serializable
data class BanRequest(
    val player: String,
    val reason: String? = null,
    /** Leave out for a permanent ban. */
    val durationSeconds: Long? = null,
)

@Serializable
data class IpBanRequest(val ip: String, val reason: String? = null, val durationSeconds: Long? = null)
