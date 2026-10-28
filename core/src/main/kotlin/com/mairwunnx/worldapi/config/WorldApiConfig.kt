package com.mairwunnx.worldapi.config

import com.mairwunnx.worldapi.api.Groups
import com.mairwunnx.worldapi.api.Scope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/** Mirrors config.yml. Every field has a default, so a partial file still loads. */
@Serializable
data class WorldApiConfig(
    val http: Http = Http(),
    val cors: Cors = Cors(),
    val auth: Auth = Auth(),
    val rateLimit: RateLimits = RateLimits(),
    val privacy: Privacy = Privacy(),
    val websocket: WebSocket = WebSocket(),
    val events: Events = Events(),
    val extensions: Extensions = Extensions(),
    val limits: Limits = Limits(),
    val scripts: Scripts = Scripts(),
    val panel: PanelSettings = PanelSettings(),
    val database: DatabaseSettings = DatabaseSettings(),
)

@Serializable
data class Http(
    val host: String = "0.0.0.0",
    val port: Int = 8110,
    val basePath: String = "/api/v1",
    /** Take the client address from X-Forwarded-For. Turn on only behind a proxy you control. */
    val trustForwardedFor: Boolean = false,
    /** Proxies whose X-Forwarded-For counts; anyone else's header is ignored. Addresses or CIDR ranges. */
    val trustedProxies: List<String> = listOf("127.0.0.1/32", "::1/128", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16"),
    /** Larger request bodies are refused with 413 before anything reads them. */
    val maxBodyBytes: Long = 1_048_576,
    /** How long a request waits for the server thread before it gives up with 503. */
    val mainThreadTimeoutMillis: Long = 5_000,
)

@Serializable
data class Cors(val allowedOrigins: List<String> = emptyList())

@Serializable
data class Auth(
    val tokens: List<Token> = emptyList(),
    /** Named sets of permissions and other groups that tokens and accounts list by name. */
    val groups: Map<String, List<String>> = Scope.defaultGroups,
    val anonymous: Anonymous = Anonymous(),
) {
    @Transient
    val resolver = Groups(groups)
}

@Serializable
data class Token(
    val name: String,
    val token: String,
    /** Permissions and group names, such as `[viewer, world:write]`. */
    val scopes: List<String>,
    val rateLimit: RateLimit? = null,
)

@Serializable
data class Anonymous(
    val enabled: Boolean = true,
    /** Paths under basePath that clients without a token may GET. `*` matches one segment, `**` any number. */
    val endpoints: List<String> = listOf(
        "/server",
        "/server/performance",
        "/worlds",
        "/worlds/*",
        "/players",
        "/players/*",
        "/players/*/statistics",
        "/players/*/advancements",
        "/leaderboards",
        "/leaderboards/*",
        "/aggregates",
        "/events",
        // Plugins' data: each resource is still closed unless its plugin marks it public.
        "/extensions",
        "/extensions/**",
        "/openapi.json",
        "/docs",
        "/ws",
    ),
)

@Serializable
data class RateLimits(
    val anonymous: RateLimit = RateLimit(requestsPerMinute = 120, burst = 30),
    val tokens: RateLimit = RateLimit(requestsPerMinute = 1_200, burst = 200),
)

@Serializable
data class RateLimit(val requestsPerMinute: Int, val burst: Int)

@Serializable
data class Privacy(val anonymous: AnonymousPrivacy = AnonymousPrivacy())

/** What clients without a token may see. Token holders see everything; IP addresses need the admin scope. */
@Serializable
data class AnonymousPrivacy(
    val coordinates: Boolean = false,
    val inventories: Boolean = false,
    val addresses: Boolean = false,
    val chat: Boolean = false,
)

@Serializable
data class WebSocket(
    val heartbeatSeconds: Int = 20,
    val maxClients: Int = 64,
    /** Connections one IP address (anonymous) or one token may hold at once. */
    val maxClientsPerClient: Int = 8,
    /** Events a slow client may fall behind by before the oldest get dropped. */
    val queueSize: Int = 256,
    val anonymousTopics: List<String> = listOf(
        "player.join",
        "player.leave",
        "player.death",
        "player.advancement",
        "player.sleep",
        "world.time",
        "world.weather",
        "world.night_skipped",
        "entity.boss_killed",
        "raid.start",
        "raid.finish",
        "server.performance",
        "server.status",
    ),
    val timeIntervalSeconds: Int = 1,
    val performanceIntervalSeconds: Int = 5,
    val moveIntervalMillis: Long = 1_000,
    val blockWindowSeconds: Int = 5,
)

/** Other plugins' data, through the expansion API. */
@Serializable
data class Extensions(
    val enabled: Boolean = true,
    /**
     * Kept from anonymous clients even where the plugin marks it public: a namespace (`mobosses`),
     * one of its resources (`mobosses/bosses`), or one of its topics (`ext.mobosses.boss.spawned`).
     */
    val private: List<String> = emptyList(),
)

@Serializable
data class Events(
    /** Record chat in the history and stream it over the WebSocket. Off by default: chat is private. */
    val chat: Boolean = false,
    /** Days to keep event history and audit records; 0 keeps them forever. */
    val retentionDays: Int = 0,
)

@Serializable
data class Limits(
    val maxPageSize: Int = 500,
    val maxFillBlocks: Int = 32_768,
    /** Blocks the undo journal keeps in memory, across every change; the oldest changes drop out first. 0 keeps none. */
    val undoBlocks: Int = 262_144,
    /** Blocks one area read (GET /worlds/{world}/blocks) may cover; it runs on the server thread. */
    val maxReadBlocks: Int = 32_768,
    val maxSpawnCount: Int = 50,
    val maxMessageLength: Int = 512,
    val maxExplosionPower: Float = 10f,
    val maxParticleCount: Int = 1_000,
    val maxEntityRadius: Double = 256.0,
    /** Entities one bulk removal may take. */
    val maxEntityRemoval: Int = 1_000,
    val maxStructureRadius: Int = 100,
    /** Blocks; block search reads every loaded chunk in range, off the server thread. */
    val maxBlockSearchRadius: Int = 64,
    /** Blocks; biome search runs on the server thread. */
    val maxBiomeSearchRadius: Int = 2_048,
    /** Console lines kept in memory for GET /server/logs. */
    val consoleLines: Int = 1_000,
    /** Text one /files/read answer may hold, in characters. */
    val maxReadChars: Int = 262_144,
    /** Earlier versions kept per file that /files changed, in WorldAPI's backups folder. */
    val fileBackups: Int = 10,
)

@Serializable
data class PanelSettings(
    /** Where people open WorldAPI from outside, such as https://mc.example.com/worldapi; /worldapi admin then sends a link. */
    val publicUrl: String = "",
    /** Item icons in the panel: WorldAPI downloads the game's client from Mojang once (about 30 MB) and reads textures from it. */
    val icons: Boolean = true,
)

@Serializable
data class Scripts(
    /** Off by default: a script is arbitrary code on the server, whatever the filters below catch. */
    val enabled: Boolean = false,
    val maxLoaded: Int = 20,
    /** How long a one-off script may run before the caller stops waiting. */
    val runTimeoutMillis: Long = 10_000,
    val logLines: Int = 500,
    /**
     * Classes and methods compiled scripts may not call, as `java/lang/ProcessBuilder` (the whole
     * class), `java/lang/System#exit` (one method), or `java/lang/reflect/` (a package).
     */
    val blockedApis: List<String> = listOf(
        "java/lang/ProcessBuilder",
        "java/lang/ProcessHandle",
        "java/lang/Runtime#exec",
        "java/lang/Runtime#halt",
        "java/lang/Runtime#exit",
        "java/lang/Runtime#load",
        "java/lang/Runtime#loadLibrary",
        "java/lang/System#exit",
        "java/lang/System#load",
        "java/lang/System#loadLibrary",
        "java/lang/System#setIn",
        "java/lang/System#setOut",
        "java/lang/System#setErr",
        "java/lang/System#getenv",
        "java/lang/reflect/",
        "java/lang/invoke/MethodHandles\$Lookup#find",
        "java/lang/invoke/MethodHandles\$Lookup#unreflect",
        "java/lang/Class#forName",
        "java/lang/Class#getDeclared",
        "java/lang/Class#getMethod",
        "java/lang/Class#getField",
        "java/lang/Class#getConstructor",
        "java/lang/ClassLoader",
        "java/net/URLClassLoader",
        "java/lang/foreign/",
        "java/io/File",
        "java/io/RandomAccessFile",
        "java/nio/file/",
        "java/nio/channels/FileChannel",
    ),
)

/** Where history, sessions, the audit log, and experience totals live. Read once at startup; a change takes a restart. */
@Serializable
data class DatabaseSettings(
    val type: DatabaseType = DatabaseType.SQLITE,
    /** SQLite only: the file, relative to the plugin folder. */
    val file: String = "worldapi.db",
    val host: String = "localhost",
    /** Null means the usual port: 5432 for PostgreSQL, 3306 for MySQL. */
    val port: Int? = null,
    val name: String = "worldapi",
    val user: String = "worldapi",
    val password: String = "",
    val poolSize: Int = 4,
    /** Extra JDBC driver properties, such as sslmode for PostgreSQL. */
    val properties: Map<String, String> = emptyMap(),
)

@Serializable
enum class DatabaseType {
    @SerialName("sqlite") SQLITE,
    @SerialName("postgresql") POSTGRESQL,
    /** MySQL and MariaDB, both through the MariaDB driver. */
    @SerialName("mysql") MYSQL,
}
