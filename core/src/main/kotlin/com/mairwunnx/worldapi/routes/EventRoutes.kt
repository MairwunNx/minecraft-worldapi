package com.mairwunnx.worldapi.routes

import com.mairwunnx.worldapi.api.Scope
import com.mairwunnx.worldapi.api.endpoints
import com.mairwunnx.worldapi.api.Page
import com.mairwunnx.worldapi.api.badRequest
import com.mairwunnx.worldapi.api.pageParams
import com.mairwunnx.worldapi.api.string
import com.mairwunnx.worldapi.events.Topic
import com.mairwunnx.worldapi.events.Visibility
import com.mairwunnx.worldapi.model.EventView
import com.mairwunnx.worldapi.model.PlayerRef
import com.mairwunnx.worldapi.store.EventQuery
import java.time.Instant

val eventRoutes = endpoints("Events", Scope.EVENTS_READ) {
    get(
        "/events", "Event history: joins, deaths, advancements, skipped nights, boss kills, and more",
        description = "Newest first by default. Recorded types: ${Topic.entries.filter { it.recorded }.joinToString { "`${it.id}`" }}.",
        query = pageParams + listOf(
            string("type", "Only these types, separated by commas"),
            string("player", "Only events of this player, by UUID or name"),
            string("from", "ISO-8601 time or epoch milliseconds; events at or after it"),
            string("to", "ISO-8601 time or epoch milliseconds; events before it"),
            string("order", "Newest first or oldest first", values = listOf("newest", "oldest")),
        ),
    ) {
        val visibility = Visibility(principal, config)
        val requested = query("type")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.map { Topic.of(it) ?: badRequest("Unknown event type \"$it\"") }
        val types = (requested ?: Topic.entries.filter { it.recorded }).filter { it.recorded && visibility.allows(it) }
        val order = query("order") ?: "newest"
        if (order !in setOf("newest", "oldest")) badRequest("order must be newest or oldest")
        if (types.isEmpty()) return@get Page(emptyList<EventView>())
        // The history has no total to give, so 0 means one.
        val limit = limit.coerceAtLeast(1)
        val rows = api.history.events(
            EventQuery(
                types = types.map { it.id },
                player = query("player"),
                from = time("from"),
                to = time("to"),
                before = cursor?.let { it.toLongOrNull() ?: badRequest("Unknown cursor", "invalid_cursor") },
                limit = limit + 1,
                oldestFirst = order == "oldest",
                includeHidden = seesVanished,
            ),
        )
        // A plugin that stopped takes its topics with it; its rows wait in the history until it's back.
        val items = rows.take(limit).mapNotNull { row ->
            val topic = Topic.of(row.type) ?: return@mapNotNull null
            visibility.redact(
                EventView(row.id, row.type, Instant.ofEpochMilli(row.at).toString(), row.at, row.playerUuid?.let { PlayerRef(it, row.playerName) }, row.world, row.data),
                topic,
            )
        }
        Page(items, nextCursor = if (rows.size > limit) rows[limit - 1].id.toString() else null)
    }
}

private fun com.mairwunnx.worldapi.api.ApiCall<*>.time(name: String): Long? = query(name)?.let { text ->
    text.toLongOrNull() ?: runCatching { Instant.parse(text).toEpochMilli() }.getOrElse { badRequest("$name must be an ISO-8601 time or epoch milliseconds") }
}
