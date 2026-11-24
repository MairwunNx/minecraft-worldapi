package com.mairwunnx.worldapi.routes

import com.mairwunnx.worldapi.api.ApiCall
import com.mairwunnx.worldapi.api.ApiException
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
import com.mairwunnx.worldapi.store.TextMatch
import io.ktor.http.HttpStatusCode
import java.time.Instant

private val filterParams = listOf(
    string("type", "Only these types, separated by commas"),
    string("from", "ISO-8601 time or epoch milliseconds; events at or after it"),
    string("to", "ISO-8601 time or epoch milliseconds; events before it"),
    string("text", "Only events whose data holds this, ignoring case: words in chat, a death message, an advancement. Needs players:private, since it matches coordinates too"),
    string("words", "Only events holding any of these words, separated by commas, or every one with `match=all`. Ignores case, accents, ё, and colors. Needs players:private, as `text` does"),
    string("match", "Whether an event needs any of `text` and `words` or all of them", values = listOf("any", "all")),
)

val eventRoutes = endpoints("Events", Scope.EVENTS_READ) {
    get(
        "/events", "Event history: joins, deaths, advancements, chat, skipped nights, boss kills, and more",
        description = "Newest first by default. Recorded types: ${Topic.entries.filter { it.recorded }.joinToString { "`${it.id}`" }}. " +
            "With `text` or `words`, a page reads at most 20,000 events: it may hold fewer items than `limit` and still have a `nextCursor` to search on.",
        query = pageParams + filterParams + listOf(
            string("player", "Only events of this player, by UUID or name"),
            string("involving", "Only events of this player, by UUID or name, and private messages sent to them"),
            string("order", "Newest first or oldest first", values = listOf("newest", "oldest")),
        ),
    ) {
        val types = types()
        val order = query("order") ?: "newest"
        if (order !in setOf("newest", "oldest")) badRequest("order must be newest or oldest")
        val match = match()
        if (types.isEmpty()) return@get Page(emptyList<EventView>())
        // The history has no total to give, so 0 means one.
        val limit = limit.coerceAtLeast(1)
        val eventQuery =
            EventQuery(
                types = types.map { it.id },
                player = query("player"),
                involving = query("involving"),
                from = time("from"),
                to = time("to"),
                before = cursor?.let { it.toLongOrNull() ?: badRequest("Unknown cursor", "invalid_cursor") },
                limit = limit + 1,
                oldestFirst = order == "oldest",
                includeHidden = seesVanished,
            )
        val (rows, resumeAt) = if (match == null) api.history.events(eventQuery) to null else api.history.searchEvents(eventQuery, match)
        val visibility = Visibility(principal, config)
        // A plugin that stopped takes its topics with it; its rows wait in the history until it's back.
        val items = rows.take(limit).mapNotNull { row ->
            val topic = Topic.of(row.type) ?: return@mapNotNull null
            visibility.redact(
                EventView(row.id, row.type, Instant.ofEpochMilli(row.at).toString(), row.at, row.playerUuid?.let { PlayerRef(it, row.playerName) }, row.world, row.data),
                topic,
            )
        }
        Page(items, nextCursor = if (rows.size > limit) rows[limit - 1].id.toString() else resumeAt?.toString())
    }

}

/** The recorded types asked for, or all of them, that this client may see. */
private fun ApiCall<*>.types(): List<Topic> {
    val visibility = Visibility(principal, config)
    val requested = query("type")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.map { Topic.of(it) ?: badRequest("Unknown event type \"$it\"") }
    return (requested ?: Topic.entries.filter { it.recorded }).filter { it.recorded && visibility.allows(it) }
}

/** `text` and `words` as one match, or null without them. */
private fun ApiCall<*>.match(): TextMatch? {
    val terms = listOfNotNull(query("text")) + query("words")?.split(',').orEmpty().map { it.trim() }.filter { it.isNotEmpty() }
    val all = when (query("match") ?: "any") {
        "any" -> false
        "all" -> true
        else -> badRequest("match must be any or all")
    }
    if (terms.isEmpty()) return null
    // The data holds coordinates the answer would strip: matching on them would tell them anyway.
    if (!seesCoordinates) throw ApiException(HttpStatusCode.Forbidden, "private", "Searching by text needs players:private")
    return TextMatch(terms, all).takeUnless { it.empty } ?: badRequest("text and words hold no letters to look for")
}

private fun ApiCall<*>.time(name: String): Long? = query(name)?.let { text ->
    text.toLongOrNull() ?: runCatching { Instant.parse(text).toEpochMilli() }.getOrElse { badRequest("$name must be an ISO-8601 time or epoch milliseconds") }
}
