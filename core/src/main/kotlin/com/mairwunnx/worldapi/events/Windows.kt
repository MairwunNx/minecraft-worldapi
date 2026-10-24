package com.mairwunnx.worldapi.events

import com.mairwunnx.worldapi.model.PlayerRef
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Adds up frequent events (block breaks and places, crafting, mob deaths) over a short window
 * and sends one summary per player and world instead of one message per block. Server thread only.
 */
class Windows(private val bus: EventBus, private val windowSeconds: () -> Int) {
    private data class Key(val topic: Topic, val player: PlayerRef?, val world: String)

    private val counts = mutableMapOf<Key, MutableMap<String, Int>>()
    private var startedAt = System.currentTimeMillis()

    fun block(topic: Topic, player: PlayerRef, world: String, type: String) = add(Key(topic, player, world), type, 1)

    fun craft(player: PlayerRef, world: String, type: String, amount: Int) = add(Key(Topic.PLAYER_CRAFT, player, world), type, amount)

    fun entityDeath(world: String, type: String) = add(Key(Topic.ENTITY_DEATH, null, world), type, 1)

    private fun add(key: Key, type: String, amount: Int) {
        if (!bus.interest(key.topic)) return
        counts.getOrPut(key) { mutableMapOf() }.merge(type, amount, Int::plus)
    }

    /** Called every tick or so; sends the summaries once the window has passed. */
    fun flushIfDue(now: Long = System.currentTimeMillis()) {
        if (now - startedAt < windowSeconds() * 1000L) return
        val seconds = ((now - startedAt) / 1000).toInt()
        startedAt = now
        if (counts.isEmpty()) return
        val batch = counts.toMap()
        counts.clear()
        for ((key, types) in batch) {
            bus.publish(ApiEvent(key.topic, buildJsonObject {
                put("windowSeconds", seconds)
                put("total", types.values.sum())
                putJsonObject("counts") { types.toSortedMap().forEach { (type, count) -> put(type, count) } }
            }, key.player, key.world))
        }
    }
}
