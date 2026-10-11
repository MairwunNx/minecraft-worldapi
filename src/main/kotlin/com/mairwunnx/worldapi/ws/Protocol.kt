package com.mairwunnx.worldapi.ws

import com.mairwunnx.worldapi.api.ErrorDetail
import com.mairwunnx.worldapi.model.EventView
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What clients send. Unknown fields are rejected, like in REST. */
@Serializable
data class ClientMessage(
    val op: String,
    val token: String? = null,
    val topics: List<String> = emptyList(),
    val filter: Filter? = null,
)

/** Narrows a subscription to one player or one world. */
@Serializable
data class Filter(
    /** UUID or name. */
    val player: String? = null,
    val world: String? = null,
)

@Serializable
sealed interface ServerMessage

@Serializable
@SerialName("hello")
data class Hello(val heartbeatSeconds: Int, val authenticated: Boolean, val scopes: List<String>, val topics: List<String>) : ServerMessage

@Serializable
@SerialName("authenticated")
data class Authenticated(val name: String, val scopes: List<String>, val topics: List<String>) : ServerMessage

@Serializable
@SerialName("subscribed")
data class Subscribed(val topics: List<String>, val filter: Filter? = null) : ServerMessage

@Serializable
@SerialName("unsubscribed")
data class Unsubscribed(val topics: List<String>) : ServerMessage

/** The current state of a topic, sent right after subscribing; events follow as they happen. */
@Serializable
@SerialName("snapshot")
data class Snapshot(val topic: String, val events: List<EventView>) : ServerMessage

@Serializable
@SerialName("event")
data class EventMessage(val topic: String, val event: EventView) : ServerMessage

/** The client fell behind and this many events were thrown away. */
@Serializable
@SerialName("dropped")
data class Dropped(val count: Int) : ServerMessage

@Serializable
@SerialName("heartbeat")
data class Heartbeat(val at: String) : ServerMessage

@Serializable
@SerialName("pong")
data object Pong : ServerMessage

@Serializable
@SerialName("error")
data class ErrorMessage(val error: ErrorDetail) : ServerMessage
