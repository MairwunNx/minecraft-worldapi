package com.mairwunnx.worldapi.events

import com.mairwunnx.worldapi.bukkit.Vanished
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
    /** About a vanished player: only admin clients get it, live or from the history. */
    val hidden: Boolean = false,
) {
    fun view(id: Long? = null) = EventView(id, topic.id, at.toString(), at.toEpochMilli(), player, world, data)
}

fun interface EventSink {
    fun accept(event: ApiEvent)
}

/** Fans events out to the history and every sink; called on the server thread, never blocks. */
class EventBus(private val history: History, private val vanished: Vanished = Vanished(), private val recordChat: () -> Boolean) {
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
        val marked = mark(event)
        if (marked.topic.recorded && (marked.topic != Topic.PLAYER_CHAT || recordChat())) {
            history.record(marked.topic.id, marked.at, marked.player?.uuid, marked.player?.name, marked.world, marked.data, marked.hidden)
        }
        sinks.forEach { it.accept(marked) }
    }

    /** An event about a vanished player is hidden from everyone but admin clients. */
    private fun mark(event: ApiEvent) =
        if (!event.hidden && event.player?.uuid?.let { it in vanished } == true) event.copy(hidden = true) else event

    fun wanted(topic: Topic) = topic.recorded || interest(topic)

    /** Streams an event without recording it, for status updates too frequent to keep. */
    fun stream(event: ApiEvent) = mark(event).let { marked -> sinks.forEach { it.accept(marked) } }
}
