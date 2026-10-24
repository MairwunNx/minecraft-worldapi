package com.mairwunnx.worldapi.events

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Who is vanished, readable from any thread. Vanish plugins and mods share no event, so the server thread keeps this
 * current: a refresh every few ticks, and a check right before an event about a player goes out.
 */
class Vanished {
    private val uuids = ConcurrentHashMap.newKeySet<UUID>()

    operator fun contains(uuid: UUID) = uuid in uuids

    operator fun contains(uuid: String) = runCatching { UUID.fromString(uuid) }.getOrNull()?.let { it in uuids } ?: false

    /**
     * Adds the player if they're vanished now. It never removes anyone: a vanish plugin may drop its mark before
     * WorldAPI hears about the quit, and the refresh takes care of players who reappear.
     */
    fun check(uuid: UUID, vanished: Boolean) {
        if (vanished) uuids += uuid
    }

    fun add(uuid: UUID) {
        uuids += uuid
    }

    /** The players vanished right now, from the server thread. */
    fun refresh(now: Set<UUID>) {
        uuids.retainAll(now)
        uuids.addAll(now)
    }
}
