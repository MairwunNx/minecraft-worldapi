package com.mairwunnx.worldapi.store

import com.mairwunnx.worldapi.api.ApiJson
import kotlinx.serialization.json.JsonObject
import java.time.Instant

data class StoredEvent(
    val id: Long,
    val type: String,
    val at: Long,
    val playerUuid: String?,
    val playerName: String?,
    val world: String?,
    val data: JsonObject,
)

data class EventQuery(
    val types: List<String> = emptyList(),
    val player: String? = null,
    val from: Long? = null,
    val to: Long? = null,
    /** Only events with an id below this one: the cursor for the next, older page. */
    val before: Long? = null,
    val limit: Int = 100,
    val oldestFirst: Boolean = false,
)

data class Session(val id: Long, val playerUuid: String, val playerName: String, val joinedAt: Long, val leftAt: Long?, val closedByRestart: Boolean)

data class AuditEntry(val token: String, val method: String, val path: String, val target: String?, val status: Int, val detail: String?)

/** The queries behind the history endpoints. */
class History(private val store: Store) {
    fun record(type: String, at: Instant, playerUuid: String?, playerName: String?, world: String?, data: JsonObject) = store.write {
        prepareStatement("INSERT INTO events (type, at, player_uuid, player_name, world, data) VALUES (?, ?, ?, ?, ?, ?)")
            .use { it.bind(type, at.toEpochMilli(), playerUuid, playerName, world, ApiJson.encodeToString(JsonObject.serializer(), data)).executeUpdate() }
    }

    suspend fun events(query: EventQuery): List<StoredEvent> = store.read {
        val where = mutableListOf<String>()
        val args = mutableListOf<Any?>()
        if (query.types.isNotEmpty()) {
            where += "type IN (${query.types.joinToString(",") { "?" }})"
            args.addAll(query.types)
        }
        query.player?.let { where += "(player_uuid = ? OR lower(player_name) = lower(?))"; args += it; args += it }
        query.from?.let { where += "at >= ?"; args += it }
        query.to?.let { where += "at < ?"; args += it }
        query.before?.let { where += if (query.oldestFirst) "id > ?" else "id < ?"; args += it }
        val sql = buildString {
            append("SELECT * FROM events")
            if (where.isNotEmpty()) append(" WHERE ").append(where.joinToString(" AND "))
            append(if (query.oldestFirst) " ORDER BY id ASC" else " ORDER BY id DESC")
            append(" LIMIT ?")
        }
        args += query.limit
        prepareStatement(sql).use { statement ->
            statement.bind(*args.toTypedArray()).executeQuery().rows {
                StoredEvent(
                    getLong("id"), getString("type"), getLong("at"), getString("player_uuid"), getString("player_name"),
                    getString("world"), ApiJson.decodeFromString(JsonObject.serializer(), getString("data")),
                )
            }
        }
    }

    suspend fun firstEventAt(type: String): Long? = store.read {
        prepareStatement("SELECT min(at) AS at FROM events WHERE type = ?").use { it.bind(type).executeQuery().rows { longOrNull("at") }.firstOrNull() }
    }

    fun openSession(uuid: String, name: String, at: Instant) = store.write {
        prepareStatement("INSERT INTO sessions (player_uuid, player_name, joined_at) VALUES (?, ?, ?)")
            .use { it.bind(uuid, name, at.toEpochMilli()).executeUpdate() }
    }

    fun closeSession(uuid: String, at: Instant) = store.write {
        prepareStatement("UPDATE sessions SET left_at = ? WHERE player_uuid = ? AND left_at IS NULL")
            .use { it.bind(at.toEpochMilli(), uuid).executeUpdate() }
    }

    /** Sessions left open by a crash end at the last moment the store knew the server was up. */
    fun closeStaleSessions(at: Instant) = store.blocking {
        prepareStatement("UPDATE sessions SET left_at = ?, closed_by_restart = 1 WHERE left_at IS NULL")
            .use { it.bind(at.toEpochMilli()).executeUpdate() }
    }

    suspend fun sessions(uuid: String, before: Long?, limit: Int): List<Session> = store.read {
        val sql = "SELECT * FROM sessions WHERE player_uuid = ?" + (if (before != null) " AND id < ?" else "") + " ORDER BY id DESC LIMIT ?"
        val args = listOfNotNull<Any>(uuid, before, limit)
        prepareStatement(sql).use { statement ->
            statement.bind(*args.toTypedArray()).executeQuery().rows {
                Session(getLong("id"), getString("player_uuid"), getString("player_name"), getLong("joined_at"), longOrNull("left_at"), getInt("closed_by_restart") == 1)
            }
        }
    }

    fun audit(entry: AuditEntry) = store.write {
        prepareStatement("INSERT INTO audit (at, token, method, path, target, status, detail) VALUES (?, ?, ?, ?, ?, ?, ?)")
            .use { it.bind(System.currentTimeMillis(), entry.token, entry.method, entry.path, entry.target, entry.status, entry.detail).executeUpdate() }
    }

    /** The last time anything was written, to close sessions a crash left open. */
    fun lastActivity(): Long? = store.blocking {
        prepareStatement("SELECT max(at) AS at FROM (SELECT max(at) AS at FROM events UNION ALL SELECT max(joined_at) FROM sessions)")
            .use { it.executeQuery().rows { longOrNull("at") }.firstOrNull() }
    }

    fun prune(retentionDays: Int) {
        if (retentionDays <= 0) return
        val cutoff = System.currentTimeMillis() - retentionDays * 86_400_000L
        store.write {
            prepareStatement("DELETE FROM events WHERE at < ?").use { it.bind(cutoff).executeUpdate() }
            prepareStatement("DELETE FROM audit WHERE at < ?").use { it.bind(cutoff).executeUpdate() }
        }
    }
}
