package com.mairwunnx.worldapi.config

import com.mairwunnx.worldapi.api.Scope
import kotlinx.serialization.Serializable

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
    val limits: Limits = Limits(),
)

@Serializable
data class Http(
    val host: String = "0.0.0.0",
    val port: Int = 8100,
    val basePath: String = "/api/v1",
    /** Take the client address from X-Forwarded-For. Turn on only behind a proxy you control. */
    val trustForwardedFor: Boolean = false,
    /** How long a request waits for the server thread before it gives up with 503. */
    val mainThreadTimeoutMillis: Long = 5_000,
)

@Serializable
data class Cors(val allowedOrigins: List<String> = emptyList())

@Serializable
data class Auth(
    val tokens: List<Token> = emptyList(),
    val anonymous: Anonymous = Anonymous(),
)

@Serializable
data class Token(
    val name: String,
    val token: String,
    val scopes: List<Scope>,
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
    val maxSpawnCount: Int = 50,
    val maxMessageLength: Int = 512,
    val maxExplosionPower: Float = 10f,
    val maxParticleCount: Int = 1_000,
    val maxEntityRadius: Double = 256.0,
    val maxStructureRadius: Int = 100,
)

