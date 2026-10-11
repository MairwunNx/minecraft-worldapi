package com.mairwunnx.worldapi.events

import com.mairwunnx.worldapi.model.EventView
import com.mairwunnx.worldapi.model.PlayerRef
import com.mairwunnx.worldapi.store.History
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/** Something that happened, on its way to the history and to WebSocket clients. */
data class ApiEvent(
    val topic: Topic,
    val data: JsonObject,
    val player: PlayerRef? = null,
    val world: String? = null,
    /** Which thing a state topic describes, so newer values replace older ones for slow clients. */
    val subject: String? = null,
    val at: Instant = Instant.now(),
) {
    fun view(id: Long? = null) = EventView(id, topic.id, at.toString(), at.toEpochMilli(), player, world, data)
}

fun interface EventSink {
    fun accept(event: ApiEvent)
}

/** Fans events out to the history and every sink; called on the server thread, never blocks. */
class EventBus(private val history: History, private val recordChat: () -> Boolean) {
    private val sinks = CopyOnWriteArrayList<EventSink>()

    /** Lets producers skip work nobody listens to, such as sampling positions every second. */
    @Volatile
    var interest: (Topic) -> Boolean = { false }

    fun subscribe(sink: EventSink) {
        sinks += sink
    }

    fun unsubscribe(sink: EventSink) {
        sinks -= sink
    }

    fun publish(event: ApiEvent) {
        if (event.topic.recorded && (event.topic != Topic.PLAYER_CHAT || recordChat())) {
            history.record(event.topic.id, event.at, event.player?.uuid, event.player?.name, event.world, event.data)
        }
        sinks.forEach { it.accept(event) }
    }

    fun wanted(topic: Topic) = topic.recorded || interest(topic)

    /** Streams an event without recording it, for status updates too frequent to keep. */
    fun stream(event: ApiEvent) = sinks.forEach { it.accept(event) }
}
