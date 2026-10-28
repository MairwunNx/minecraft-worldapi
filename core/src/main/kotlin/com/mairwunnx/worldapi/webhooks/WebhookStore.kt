package com.mairwunnx.worldapi.webhooks

import com.mairwunnx.worldapi.api.ApiJson
import com.mairwunnx.worldapi.config.Webhook
import com.mairwunnx.worldapi.store.Store
import com.mairwunnx.worldapi.store.WebhookRows
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

/**
 * Webhooks made in game, in the panel, or through the API, in the database. Senders read them on every event, so they
 * stay in memory too; only this class changes them.
 */
class WebhookStore(private val store: Store) {
    class Stored(val hook: Webhook, val createdAt: Long, val createdBy: String)

    private val changes = Mutex()

    @Volatile
    var all: List<Stored> = emptyList()
        private set

    /** At start, on the write thread. A row a newer WorldAPI wrote and this one can't read is skipped, not lost. */
    fun load() {
        all = store.blocking {
            WebhookRows.selectAll().orderBy(WebhookRows.name, SortOrder.ASC).mapNotNull { row ->
                runCatching { Stored(ApiJson.decodeFromString(Webhook.serializer(), row[WebhookRows.data]), row[WebhookRows.createdAt], row[WebhookRows.createdBy]) }.getOrNull()
            }
        }
    }

    fun find(name: String) = all.firstOrNull { it.hook.name == name }

    /** Saves a new webhook; false when one of that name exists. Throws when the database refuses, and keeps nothing then. */
    suspend fun add(hook: Webhook, createdBy: String): Boolean = changes.withLock {
        if (find(hook.name) != null) return@withLock false
        val now = System.currentTimeMillis()
        store.writeNow {
            WebhookRows.insert {
                it[name] = hook.name
                it[data] = ApiJson.encodeToString(Webhook.serializer(), hook)
                it[createdAt] = now
                it[WebhookRows.createdBy] = createdBy
            }
        }
        all = (all + Stored(hook, now, createdBy)).sortedBy { it.hook.name }
        true
    }

    suspend fun replace(hook: Webhook) = changes.withLock {
        store.writeNow { WebhookRows.update({ WebhookRows.name eq hook.name }) { it[data] = ApiJson.encodeToString(Webhook.serializer(), hook) } }
        all = all.map { if (it.hook.name == hook.name) Stored(hook, it.createdAt, it.createdBy) else it }
    }

    suspend fun remove(name: String): Boolean = changes.withLock {
        if (find(name) == null) return@withLock false
        store.writeNow { WebhookRows.deleteWhere { WebhookRows.name eq name } }
        all = all.filterNot { it.hook.name == name }
        true
    }
}
