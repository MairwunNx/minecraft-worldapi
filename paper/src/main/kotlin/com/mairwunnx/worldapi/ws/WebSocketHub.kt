package com.mairwunnx.worldapi.ws

import com.mairwunnx.worldapi.api.ErrorDetail
import com.mairwunnx.worldapi.api.Principal
import com.mairwunnx.worldapi.api.RateLimiter
import com.mairwunnx.worldapi.config.RateLimit
import com.mairwunnx.worldapi.config.WorldApiConfig
import com.mairwunnx.worldapi.events.ApiEvent
import com.mairwunnx.worldapi.events.EventSink
import com.mairwunnx.worldapi.events.Topic
import com.mairwunnx.worldapi.events.Visibility
import io.ktor.websocket.CloseReason
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** Builds the current state of a topic for a new subscriber. Runs on the server thread. */
fun interface SnapshotSource {
    suspend fun snapshot(topic: Topic, filter: Filter?): List<ApiEvent>
}

/**
 * Streams events to WebSocket clients. Each client has its own outbox: state topics keep
 * only the newest value per subject, discrete events queue up to websocket.queueSize and then
 * drop oldest-first. A slow client falls behind on its own; it never holds up the server or
 * other clients, and memory stays bounded.
 */
class WebSocketHub(
    private val config: () -> WorldApiConfig,
    private val authenticate: (String) -> Principal?,
    private val snapshots: SnapshotSource,
) : EventSink {
    private val clients = ConcurrentHashMap.newKeySet<Client>()

    val size get() = clients.size

    override fun accept(event: ApiEvent) {
        clients.forEach { it.offer(event) }
    }

    fun wants(topic: Topic) = clients.any { it.subscriptions.containsKey(topic) }

    fun closeAll(reason: String) = runBlocking {
        clients.forEach { runCatching { it.session.close(CloseReason(CloseReason.Codes.GOING_AWAY, reason)) } }
        clients.clear()
    }

    suspend fun serve(session: DefaultWebSocketSession, initial: Principal) {
        val settings = config()
        if (clients.size >= settings.websocket.maxClients) {
            session.close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "Too many clients"))
            return
        }
        // One IP or one token can't take every slot and lock everyone else out.
        if (clients.count { it.key == initial.key } >= settings.websocket.maxClientsPerClient) {
            session.close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "Too many connections from this client"))
            return
        }
        val client = Client(session, initial, settings.websocket.queueSize)
        clients += client
        try {
            client.run(session)
        } finally {
            clients -= client
        }
    }

    private inner class Client(val session: DefaultWebSocketSession, @Volatile var principal: Principal, private val queueSize: Int) {
        /** Fixed at connect time: the slot counts against whoever opened it. */
        val key = principal.key
        val subscriptions = ConcurrentHashMap<Topic, Filter>()
        private val lock = Any()
        private val events = ArrayDeque<ApiEvent>()
        private val states = LinkedHashMap<String, ApiEvent>()
        private var dropped = 0
        private val wake = Channel<Unit>(Channel.CONFLATED)
        private val messages = RateLimiter()

        /** No header token and no anonymous access: the client must send a token before anything else. */
        private val needsAuth get() = principal.scopes.isEmpty()
        private fun visibility() = Visibility(principal, config())

        fun offer(event: ApiEvent) {
            val filter = subscriptions[event.topic] ?: return
            if (!matches(event, filter)) return
            synchronized(lock) {
                if (event.topic.state) {
                    states["${event.topic.id}/${event.subject ?: event.player?.uuid ?: event.world ?: ""}"] = event
                } else {
                    if (events.size >= queueSize) {
                        events.removeFirst()
                        dropped++
                    }
                    events.addLast(event)
                }
            }
            wake.trySend(Unit)
        }

        private fun matches(event: ApiEvent, filter: Filter): Boolean {
            filter.player?.let { wanted ->
                val player = event.player ?: return false
                if (!player.uuid.equals(wanted, ignoreCase = true) && !player.name.equals(wanted, ignoreCase = true)) return false
            }
            filter.world?.let { if (!event.world.equals(it, ignoreCase = true)) return false }
            return true
        }

        suspend fun run(scope: CoroutineScope) {
            val settings = config()
            send(Hello(settings.websocket.heartbeatSeconds, !principal.anonymous, principal.scopes.map { it.id }, available()))
            val writer = scope.launch { drain() }
            val heartbeat = scope.launch {
                while (isActive) {
                    delay(settings.websocket.heartbeatSeconds * 1000L)
                    send(Heartbeat(Instant.now().toString()))
                }
            }
            try {
                if (needsAuth) {
                    val first = withTimeoutOrNull(10_000) { session.incoming.receiveCatching().getOrNull() }
                    val message = (first as? Frame.Text)?.let { parse(it.readText()) }
                    if (message?.op != "auth" || !handle(message)) {
                        session.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Authenticate first"))
                        return
                    }
                }
                for (frame in session.incoming) {
                    if (frame !is Frame.Text) continue
                    // Each subscribe builds snapshots on the server thread and each auth checks a token: a flood waits its turn.
                    val wait = messages.acquire("client", MESSAGE_LIMIT)
                    if (wait > 0) {
                        send(ErrorMessage(ErrorDetail("rate_limited", "Too many messages; retry in $wait s")))
                        continue
                    }
                    val message = parse(frame.readText()) ?: continue
                    handle(message)
                }
            } finally {
                writer.cancel()
                heartbeat.cancel()
            }
        }

        private suspend fun handle(message: ClientMessage): Boolean {
            when (message.op) {
                "auth" -> {
                    val principal = message.token?.let(authenticate)
                    if (principal == null) {
                        send(ErrorMessage(ErrorDetail("invalid_token", "The token isn't valid")))
                        return false
                    }
                    this.principal = principal
                    // Scopes may have widened or narrowed; drop what the new principal can't see.
                    subscriptions.keys.removeIf { !visibility().allows(it) }
                    send(Authenticated(principal.name, principal.scopes.map { it.id }, available()))
                }
                "subscribe" -> subscribe(message)
                "unsubscribe" -> {
                    val topics = message.topics.mapNotNull(Topic::of)
                    topics.forEach(subscriptions::remove)
                    send(Unsubscribed(topics.map { it.id }))
                }
                "ping" -> send(Pong)
                else -> send(ErrorMessage(ErrorDetail("unknown_op", "Unknown op \"${message.op}\"; use auth, subscribe, unsubscribe, or ping")))
            }
            return true
        }

        private suspend fun subscribe(message: ClientMessage) {
            val visibility = visibility()
            val unknown = message.topics.filter { Topic.of(it) == null }
            if (unknown.isNotEmpty()) {
                send(ErrorMessage(ErrorDetail("unknown_topic", "Unknown topics: ${unknown.joinToString()}")))
                return
            }
            val topics = message.topics.mapNotNull(Topic::of)
            val denied = topics.filterNot(visibility::allows)
            if (denied.isNotEmpty()) {
                send(ErrorMessage(ErrorDetail("topic_forbidden", "Not allowed for this client: ${denied.joinToString { it.id }}")))
            }
            val allowed = topics - denied.toSet()
            if (allowed.isEmpty()) return
            val filter = message.filter ?: Filter()
            allowed.forEach { subscriptions[it] = filter }
            send(Subscribed(allowed.map { it.id }, message.filter))
            for (topic in allowed) {
                val current = runCatching { snapshots.snapshot(topic, message.filter) }.getOrDefault(emptyList())
                if (current.isNotEmpty()) send(Snapshot(topic.id, current.filter { matches(it, filter) && visibility.shows(it) }.map { visibility.redact(it.view(), topic) }))
            }
        }

        private fun available() = Topic.entries.filter(visibility()::allows).map { it.id }

        private suspend fun drain() {
            for (signal in wake) {
                val (batch, lost) = synchronized(lock) {
                    val batch = events.toList() + states.values
                    events.clear()
                    states.clear()
                    (batch to dropped).also { dropped = 0 }
                }
                if (lost > 0) send(Dropped(lost))
                val visibility = visibility()
                for (event in batch) {
                    // The principal may have changed since the event was queued.
                    if (!visibility.allows(event.topic) || !visibility.shows(event)) continue
                    send(EventMessage(event.topic.id, visibility.redact(event.view(), event.topic)))
                }
            }
        }

        private suspend fun send(message: ServerMessage) {
            runCatching { session.send(Frame.Text(json.encodeToString(ServerMessage.serializer(), message))) }
        }

        private suspend fun parse(text: String): ClientMessage? = try {
            json.decodeFromString(ClientMessage.serializer(), text)
        } catch (error: Exception) {
            send(ErrorMessage(ErrorDetail("invalid_message", error.message?.lineSequence()?.first() ?: "Invalid JSON")))
            null
        }
    }

    companion object {
        /** What one connection may send: plenty for a page that subscribes as it opens, too little to keep the server thread busy. */
        val MESSAGE_LIMIT = RateLimit(requestsPerMinute = 120, burst = 30)

        val json = Json {
            classDiscriminator = "op"
            explicitNulls = false
            encodeDefaults = true
        }

        /** The WebSocket in the OpenAPI document; the protocol itself lives on the wiki's “WebSocket” page. */
        fun openApiPath(): JsonObject = buildJsonObject {
            putJsonObject("get") {
                put("operationId", "getWs")
                put("summary", "Stream events over a WebSocket")
                put("tags", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("Events"))))
                put(
                    "description",
                    """
                    Upgrade to a WebSocket. Authenticate with the Authorization header, or send `{"op":"auth","token":"…"}` as the first message. Never put the token in the URL.

                    Then subscribe: `{"op":"subscribe","topics":["player.join","world.time"],"filter":{"player":"Steve"}}`. The server answers `subscribed`, a `snapshot` of the current state where a topic has one, then `event` messages as things happen. `heartbeat` arrives every few seconds; `dropped` says how many events a slow client missed.

                    Topics: ${Topic.entries.joinToString { "`${it.id}`" }}.

                    Permission: `events:read`; each topic may need its own, such as `chat:read` or `logs:read`.
                    """.trimIndent(),
                )
                put("x-scope", "events:read")
                putJsonObject("responses") { putJsonObject("101") { put("description", "Switching to the WebSocket protocol") } }
            }
        }
    }
}
