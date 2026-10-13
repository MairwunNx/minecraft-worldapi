package com.mairwunnx.worldapi.stats

import com.mairwunnx.worldapi.store.Store
import com.mairwunnx.worldapi.store.bind
import com.mairwunnx.worldapi.store.rows
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class Gained(val uuid: UUID, val name: String, val points: Long)

/**
 * Experience each player has gained in total. Vanilla keeps no such statistic, so WorldAPI adds
 * up every pickup from the moment it's installed. Pickups collect in memory, since an XP farm
 * hands out dozens a second, and go to the store every few seconds and on shutdown.
 */
class Experience(private val store: Store) {
    private class Pending(val name: String, val points: Long)

    private val pending = ConcurrentHashMap<UUID, Pending>()

    fun add(uuid: UUID, name: String, points: Int) {
        if (points <= 0) return
        pending.merge(uuid, Pending(name, points.toLong())) { old, new -> Pending(new.name, old.points + new.points) }
    }

    fun flush() {
        val batch = pending.keys.mapNotNull { uuid -> pending.remove(uuid)?.let { uuid to it } }
        if (batch.isEmpty()) return
        store.write {
            prepareStatement(
                """INSERT INTO experience (player_uuid, player_name, gained) VALUES (?, ?, ?)
                   ON CONFLICT(player_uuid) DO UPDATE SET player_name = excluded.player_name, gained = gained + excluded.gained""",
            ).use { statement -> batch.forEach { (uuid, it) -> statement.bind(uuid.toString(), it.name, it.points).executeUpdate() } }
        }
    }

    /** Everyone's total, including pickups that haven't reached the store yet. */
    suspend fun totals(): List<Gained> {
        val stored = store.read {
            prepareStatement("SELECT player_uuid, player_name, gained FROM experience").use {
                it.executeQuery().rows { Gained(UUID.fromString(getString("player_uuid")), getString("player_name"), getLong("gained")) }
            }
        }.associateBy { it.uuid }.toMutableMap()
        pending.forEach { (uuid, it) ->
            stored.merge(uuid, Gained(uuid, it.name, it.points)) { old, new -> Gained(uuid, new.name, old.points + new.points) }
        }
        return stored.values.toList()
    }
}
