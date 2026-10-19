package com.mairwunnx.worldapi.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class EventView(
    /** Present for events from the history; streamed state updates have none. */
    val id: Long? = null,
    /** The topic, such as `player.join` or `world.night_skipped`. */
    val type: String,
    val at: String,
    /** The same moment in milliseconds since the epoch. */
    val atMillis: Long,
    val player: PlayerRef? = null,
    val world: String? = null,
    /** Fields depend on the type; see the topic list on the wiki's “WebSocket” page. */
    val data: JsonObject,
)

@Serializable
data class SessionView(
    val joinedAt: String,
    /** Absent while the player is still online. */
    val leftAt: String? = null,
    val seconds: Long,
    /** The server stopped without WorldAPI seeing the player leave; leftAt is a best guess. */
    val closedByRestart: Boolean = false,
)
