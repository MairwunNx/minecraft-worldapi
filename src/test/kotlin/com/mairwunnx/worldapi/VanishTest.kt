package com.mairwunnx.worldapi

import com.mairwunnx.worldapi.bukkit.Vanished
import com.mairwunnx.worldapi.events.ApiEvent
import com.mairwunnx.worldapi.events.EventBus
import com.mairwunnx.worldapi.events.Topic
import com.mairwunnx.worldapi.model.PlayerRef
import com.mairwunnx.worldapi.store.EventQuery
import com.mairwunnx.worldapi.store.History
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VanishTest {
    private val ghost = UUID.randomUUID()
    private val steve = UUID.randomUUID()

    private fun join(uuid: UUID, name: String) = ApiEvent(Topic.PLAYER_JOIN, JsonObject(emptyMap()), PlayerRef(uuid.toString(), name), "world")

    @Test
    fun `the bus hides events about vanished players, live and in the history`() = sqliteStore(Files.createTempFile("worldapi", ".db")).use { store ->
        val history = History(store)
        val vanished = Vanished().apply { add(ghost) }
        val bus = EventBus(history, vanished) { false }
        val seen = mutableListOf<ApiEvent>()
        bus.subscribe { seen += it }
        bus.publish(join(ghost, "Ghost"))
        bus.publish(join(steve, "Steve"))
        assertEquals(listOf(true, false), seen.map { it.hidden })

        store.blocking { }
        val public = runBlocking { history.events(EventQuery()) }
        val everything = runBlocking { history.events(EventQuery(includeHidden = true)) }
        assertEquals(listOf("Steve"), public.map { it.playerName })
        assertEquals(setOf("Ghost", "Steve"), everything.map { it.playerName }.toSet())
    }

    @Test
    fun `sessions played vanished show to admin clients only`() = sqliteStore(Files.createTempFile("worldapi", ".db")).use { store ->
        val history = History(store)
        history.openSession(ghost.toString(), "Ghost", Instant.ofEpochMilli(1_000))
        history.closeSession(ghost.toString(), Instant.ofEpochMilli(2_000))
        history.openSession(ghost.toString(), "Ghost", Instant.ofEpochMilli(3_000), hidden = true)
        store.blocking { }
        val public = runBlocking { history.sessions(ghost.toString(), null, 10) }
        val everything = runBlocking { history.sessions(ghost.toString(), null, 10, includeHidden = true) }
        assertEquals(listOf(1_000L), public.map { it.joinedAt })
        assertEquals(listOf(3_000L, 1_000L), everything.map { it.joinedAt })
        assertTrue(steve.toString() !in Vanished())
    }
}
