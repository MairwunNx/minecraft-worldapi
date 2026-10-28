package com.mairwunnx.worldapi.store

import com.mairwunnx.worldapi.api.ApiJson
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.core.min
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
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

private const val SEARCH_BATCH = 1_000

data class EventQuery(
    val types: List<String> = emptyList(),
    val player: String? = null,
    val from: Long? = null,
    val to: Long? = null,
    /** Only events with an id below this one: the cursor for the next, older page. */
    val before: Long? = null,
    val limit: Int = 100,
    val oldestFirst: Boolean = false,
    /** Events recorded while their player was vanished; only admin clients ask for them. */
    val includeHidden: Boolean = false,
)

data class Session(val id: Long, val playerUuid: String, val playerName: String, val joinedAt: Long, val leftAt: Long?, val closedByRestart: Boolean)

data class AuditEntry(val token: String, val method: String, val path: String, val target: String?, val status: Int, val detail: String?)

data class AuditRow(val id: Long, val at: Long, val entry: AuditEntry)

/** The queries behind the history endpoints. */
class History(private val store: Store) {
    fun record(type: String, at: Instant, playerUuid: String?, playerName: String?, world: String?, data: JsonObject, hidden: Boolean = false) = store.write {
        Events.insert {
            it[Events.hidden] = hidden
            it[Events.type] = type
            it[Events.at] = at.toEpochMilli()
            it[Events.playerUuid] = playerUuid
            it[Events.playerName] = playerName
            it[Events.world] = world
            it[Events.data] = ApiJson.encodeToString(JsonObject.serializer(), data)
        }
    }

    suspend fun events(query: EventQuery): List<StoredEvent> = store.read {
        val where = buildList {
            if (query.types.isNotEmpty()) add(Events.type inList query.types)
            query.player?.let { add((Events.playerUuid eq it) or (Events.playerName.lowerCase() eq it.lowercase())) }
            query.from?.let { add(Events.at greaterEq it) }
            query.to?.let { add(Events.at less it) }
            query.before?.let { add(if (query.oldestFirst) Events.id greater it else Events.id less it) }
            if (!query.includeHidden) add(Events.hidden eq false)
        }
        Events.selectAll()
            .apply { if (where.isNotEmpty()) where(where.reduce(Op<Boolean>::and)) }
            .orderBy(Events.id, if (query.oldestFirst) SortOrder.ASC else SortOrder.DESC)
            .limit(query.limit)
            .map { it.toEvent() }
    }

    /**
     * Events whose data holds [text], ignoring case in any language. SQLite lowers only ASCII, so the history is read in
     * batches and matched here; past [scanLimit] rows it stops and answers the last id it read, to go on from there.
     */
    suspend fun searchEvents(query: EventQuery, text: String, scanLimit: Int = 20_000): Pair<List<StoredEvent>, Long?> {
        val needle = text.lowercase()
        val found = mutableListOf<StoredEvent>()
        var before = query.before
        var scanned = 0
        while (found.size < query.limit && scanned < scanLimit) {
            val batch = events(query.copy(before = before, limit = minOf(SEARCH_BATCH, scanLimit - scanned)))
            if (batch.isEmpty()) return found to null
            scanned += batch.size
            for (event in batch) {
                if (needle !in event.data.toString().lowercase()) continue
                found += event
                if (found.size == query.limit) return found to null
            }
            before = batch.last().id
        }
        return found to before.takeIf { scanned >= scanLimit }
    }

    /** How many events the history holds, for Plan's server page. */
    suspend fun eventCount(): Long = store.read { Events.selectAll().count() }

    suspend fun firstEventAt(type: String): Long? = store.read {
        val first = Events.at.min()
        Events.select(first).where { Events.type eq type }.firstOrNull()?.get(first)
    }

    fun openSession(uuid: String, name: String, at: Instant, hidden: Boolean = false) = store.write {
        Sessions.insert {
            it[Sessions.hidden] = hidden
            it[playerUuid] = uuid
            it[playerName] = name
            it[joinedAt] = at.toEpochMilli()
        }
    }

    fun closeSession(uuid: String, at: Instant) = store.write {
        Sessions.update({ (Sessions.playerUuid eq uuid) and Sessions.leftAt.isNull() }) { it[leftAt] = at.toEpochMilli() }
    }

    /** Sessions left open by a crash end at the last moment the store knew the server was up. */
    fun closeStaleSessions(at: Instant) = store.blocking {
        Sessions.update({ Sessions.leftAt.isNull() }) {
            it[leftAt] = at.toEpochMilli()
            it[closedByRestart] = true
        }
    }

    /** A player's sessions, newest first; [includeHidden] adds the ones they played vanished. */
    suspend fun sessions(uuid: String, before: Long?, limit: Int, includeHidden: Boolean = false): List<Session> = store.read {
        Sessions.selectAll()
            .where {
                var where: Op<Boolean> = Sessions.playerUuid eq uuid
                if (before != null) where = where and (Sessions.id less before)
                if (!includeHidden) where = where and (Sessions.hidden eq false)
                where
            }
            .orderBy(Sessions.id, SortOrder.DESC)
            .limit(limit)
            .map { Session(it[Sessions.id], it[Sessions.playerUuid], it[Sessions.playerName], it[Sessions.joinedAt], it[Sessions.leftAt], it[Sessions.closedByRestart]) }
    }

    fun audit(entry: AuditEntry) = store.write {
        Audit.insert {
            it[at] = System.currentTimeMillis()
            it[token] = entry.token
            it[method] = entry.method
            it[path] = entry.path
            it[target] = entry.target
            it[status] = entry.status
            it[detail] = entry.detail
        }
    }

    /** The audit log, newest first; [actor] narrows it to one token or account. */
    suspend fun auditLog(before: Long?, limit: Int, actor: String?): List<AuditRow> = store.read {
        Audit.selectAll()
            .apply {
                val where = listOfNotNull(before?.let { Audit.id less it }, actor?.let { Audit.token eq it })
                if (where.isNotEmpty()) where(where.reduce(Op<Boolean>::and))
            }
            .orderBy(Audit.id, SortOrder.DESC)
            .limit(limit)
            .map { AuditRow(it[Audit.id], it[Audit.at], AuditEntry(it[Audit.token], it[Audit.method], it[Audit.path], it[Audit.target], it[Audit.status], it[Audit.detail])) }
    }

    /** The last time anything was written, to close sessions a crash left open. */
    fun lastActivity(): Long? = store.blocking {
        val lastEvent = Events.at.max()
        val lastJoin = Sessions.joinedAt.max()
        listOfNotNull(
            Events.select(lastEvent).firstOrNull()?.get(lastEvent),
            Sessions.select(lastJoin).firstOrNull()?.get(lastJoin),
        ).maxOrNull()
    }

    fun prune(retentionDays: Int) {
        if (retentionDays <= 0) return
        val cutoff = System.currentTimeMillis() - retentionDays * 86_400_000L
        store.write {
            Events.deleteWhere { at less cutoff }
            Audit.deleteWhere { at less cutoff }
        }
    }

    private fun ResultRow.toEvent() = StoredEvent(
        this[Events.id], this[Events.type], this[Events.at], this[Events.playerUuid], this[Events.playerName], this[Events.world],
        ApiJson.decodeFromString(JsonObject.serializer(), this[Events.data]),
    )
}
