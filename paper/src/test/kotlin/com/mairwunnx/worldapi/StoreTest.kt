package com.mairwunnx.worldapi

import com.mairwunnx.worldapi.config.DatabaseSettings
import com.mairwunnx.worldapi.config.DatabaseType
import com.mairwunnx.worldapi.stats.Experience
import com.mairwunnx.worldapi.store.AuditEntry
import com.mairwunnx.worldapi.store.EventQuery
import com.mairwunnx.worldapi.store.History
import com.mairwunnx.worldapi.store.Store
import com.mairwunnx.worldapi.store.TABLES
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StoreTest {
    @Test
    fun `a SQLite file from 1_x opens with every row in place`() {
        val db = Files.createTempFile("worldapi", ".db")
        val steve = UUID.randomUUID()
        oldSchema(db, steve)
        // A table that lacks an index gets it back.
        sqlite(db) { it.createStatement().use { statement -> statement.execute("DROP INDEX events_player") } }

        sqliteStore(db).use { store ->
            val history = History(store)
            val events = runBlocking { history.events(EventQuery(oldestFirst = true)) }
            assertEquals(listOf(1L, 2L), events.map { it.id })
            assertEquals("Steve", events[0].playerName)
            assertEquals("stone", events[1].data["block"]!!.jsonPrimitive.content)
            assertEquals(1_000L, runBlocking { history.firstEventAt("player.join") })
            val sessions = runBlocking { history.sessions(steve.toString(), null, 10) }
            assertEquals(listOf(Pair(2_000L, true), Pair(1_000L, false)), sessions.map { it.joinedAt to it.closedByRestart })
            assertEquals(mapOf("Steve" to 70L), Experience(store).let { runBlocking { it.totals() } }.associate { it.name to it.points })
            assertTrue(Experience(store).hasBaseline(steve))

            // New rows take the next ids, and the upsert works on the old table.
            history.record("player.leave", Instant.ofEpochMilli(5_000), steve.toString(), "Steve", "world", JsonObject(emptyMap()))
            Experience(store).apply { add(steve, "Steve", 5); flush() }
            assertEquals(3L, runBlocking { history.events(EventQuery()) }.first().id)
            assertEquals(75L, runBlocking { Experience(store).totals() }.single().points)
        }
        val indexes = sqlite(db) { connection ->
            connection.createStatement().use { it.executeQuery("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'events'").use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } } }
        }
        assertEquals(setOf("events_type_at", "events_player"), indexes.filterNot { it.startsWith("sqlite_") }.toSet())
    }

    @Test
    fun `text search ignores case beyond ASCII, which SQLite's LOWER doesn't`() {
        Store(DatabaseSettings(file = Files.createTempFile("worldapi", ".db").toString()), tempFolder()).use { store ->
            val history = History(store)
            val alex = UUID.randomUUID()
            history.record("player.chat", Instant.ofEpochMilli(4_000), alex.toString(), "Alex", "world", buildJsonObject { put("message", "Где АЛМАЗЫ?") })
            history.record("player.chat", Instant.ofEpochMilli(5_000), alex.toString(), "Alex", "world", buildJsonObject { put("message", "алмазы нашёл") })
            val diamonds = runBlocking { history.searchEvents(EventQuery(limit = 10), "алмазы") }
            assertEquals(listOf("алмазы нашёл", "Где АЛМАЗЫ?"), diamonds.first.map { it.data["message"]!!.jsonPrimitive.content })
            assertNull(diamonds.second)
            // Past the scan limit it stops and says where to go on.
            val first = runBlocking { history.searchEvents(EventQuery(limit = 10), "АЛМАЗЫ", scanLimit = 1) }
            assertEquals(1, first.first.size)
            val rest = runBlocking { history.searchEvents(EventQuery(limit = 10, before = first.second), "алмазы", scanLimit = 1) }
            assertEquals("Где АЛМАЗЫ?", rest.first.single().data["message"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `the store works on SQLite`() = suite(DatabaseSettings(file = Files.createTempFile("worldapi", ".db").toString()))

    /**
     * Runs the suite on real servers, when WORLDAPI_TEST_DATABASES lists them as
     * `postgresql://user:password@host:port/name` or `mysql://...`, separated by commas.
     */
    @Test
    fun `the store works on the databases in WORLDAPI_TEST_DATABASES`() {
        val urls = System.getenv("WORLDAPI_TEST_DATABASES")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        urls.forEach { url ->
            val uri = URI(url)
            val (user, password) = uri.userInfo.split(":", limit = 2)
            val settings = DatabaseSettings(
                type = DatabaseType.entries.single { it.name.equals(uri.scheme, ignoreCase = true) },
                host = uri.host, port = uri.port, name = uri.path.removePrefix("/"), user = user, password = password,
            )
            // Start from nothing, as on a fresh server.
            Store(settings, tempFolder()).use { it.blocking { SchemaUtils.drop(*TABLES) } }
            suite(settings)
            println("Store suite passed on ${uri.scheme}://${uri.host}:${uri.port}")
        }
    }

    private fun suite(settings: DatabaseSettings) {
        val folder = tempFolder()
        val steve = UUID.randomUUID()
        val alex = UUID.randomUUID()
        val big = "x".repeat(100_000)

        Store(settings, folder).use { store ->
            val history = History(store)
            history.openSession(steve.toString(), "Steve", Instant.ofEpochMilli(1_000))
            history.record("player.join", Instant.ofEpochMilli(1_000), steve.toString(), "Steve", "world", buildJsonObject { put("first", true) })
            history.record("player.chat", Instant.ofEpochMilli(2_000), alex.toString(), "Alex", "world", buildJsonObject { put("message", big) })
            history.record("world.weather", Instant.ofEpochMilli(3_000), null, null, "world", buildJsonObject { put("rain", true) })
            history.closeSession(steve.toString(), Instant.ofEpochMilli(1_500))
            history.openSession(steve.toString(), "Steve", Instant.ofEpochMilli(2_500))
            history.audit(AuditEntry("builder", "POST", "/api/v1/echo", "stone", 200, null))

            val all = runBlocking { history.events(EventQuery()) }
            assertEquals(listOf("world.weather", "player.chat", "player.join"), all.map { it.type })
            assertEquals(big, all[1].data["message"]!!.jsonPrimitive.content)
            assertEquals(listOf("player.chat"), runBlocking { history.events(EventQuery(player = "aLEX")) }.map { it.type })
            assertEquals(listOf("player.join"), runBlocking { history.events(EventQuery(player = steve.toString())) }.map { it.type })
            assertEquals(2, runBlocking { history.events(EventQuery(types = listOf("player.join", "world.weather"))) }.size)
            assertEquals(listOf("player.chat"), runBlocking { history.events(EventQuery(from = 2_000, to = 3_000)) }.map { it.type })
            val oldest = runBlocking { history.events(EventQuery(oldestFirst = true, limit = 1)) }.single()
            assertEquals("player.join", oldest.type)
            assertEquals("player.chat", runBlocking { history.events(EventQuery(oldestFirst = true, before = oldest.id, limit = 1)) }.single().type)
            assertEquals("player.chat", runBlocking { history.events(EventQuery(before = all[0].id, limit = 1)) }.single().type)
            assertEquals(1_000L, runBlocking { history.firstEventAt("player.join") })
            assertNull(runBlocking { history.firstEventAt("raid.start") })
            assertEquals(3_000L, history.lastActivity())


            val experience = Experience(store)
            experience.add(alex, "Alex", 40)
            experience.flush()
            experience.seed(steve, "Steve", 300)
            experience.seed(steve, "Steve", 900)
            // Alex counted 40 before a baseline of 25: the larger stays.
            experience.seed(alex, "Alexander", 25)
            experience.add(steve, "Steve", 10)
            experience.flush()
            assertEquals(mapOf("Steve" to 310L, "Alexander" to 40L), runBlocking { experience.totals() }.associate { it.name to it.points })
        }

        // A second start finds the schema in place and closes what a crash left open.
        Store(settings, folder).use { store ->
            val history = History(store)
            history.lastActivity()?.let { history.closeStaleSessions(Instant.ofEpochMilli(it)) }
            val sessions = runBlocking { history.sessions(steve.toString(), null, 10) }
            assertEquals(listOf(Pair(3_000L, true), Pair(1_500L, false)), sessions.map { it.leftAt to it.closedByRestart })
            assertEquals(1, runBlocking { history.sessions(steve.toString(), sessions[0].id, 10) }.size)
            val experience = Experience(store)
            assertTrue(experience.hasBaseline(steve) && experience.hasBaseline(alex))
            history.prune(1)
            assertEquals(emptyList(), runBlocking { history.events(EventQuery()) })
        }
    }

    /** Writes a database the way WorldAPI 1.1.1 did, with its own DDL and statements. */
    private fun oldSchema(db: Path, steve: UUID) {
        sqlite(db) { connection ->
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA journal_mode=WAL")
                listOf(
                    "CREATE TABLE IF NOT EXISTS events (id INTEGER PRIMARY KEY AUTOINCREMENT, type TEXT NOT NULL, at INTEGER NOT NULL, player_uuid TEXT, player_name TEXT, world TEXT, data TEXT NOT NULL)",
                    "CREATE INDEX IF NOT EXISTS events_type_at ON events(type, at)",
                    "CREATE INDEX IF NOT EXISTS events_player ON events(player_uuid, id)",
                    "CREATE TABLE IF NOT EXISTS sessions (id INTEGER PRIMARY KEY AUTOINCREMENT, player_uuid TEXT NOT NULL, player_name TEXT NOT NULL, joined_at INTEGER NOT NULL, left_at INTEGER, closed_by_restart INTEGER NOT NULL DEFAULT 0)",
                    "CREATE INDEX IF NOT EXISTS sessions_player ON sessions(player_uuid, id)",
                    "CREATE TABLE IF NOT EXISTS audit (id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, token TEXT NOT NULL, method TEXT NOT NULL, path TEXT NOT NULL, target TEXT, status INTEGER NOT NULL, detail TEXT)",
                    "CREATE TABLE IF NOT EXISTS experience (player_uuid TEXT PRIMARY KEY, player_name TEXT NOT NULL, gained INTEGER NOT NULL)",
                    "CREATE TABLE IF NOT EXISTS experience_baseline (player_uuid TEXT PRIMARY KEY, xp_total INTEGER NOT NULL, taken_at INTEGER NOT NULL)",
                    "INSERT INTO events (type, at, player_uuid, player_name, world, data) VALUES ('player.join', 1000, '$steve', 'Steve', 'world', '{}')",
                    "INSERT INTO events (type, at, player_uuid, player_name, world, data) VALUES ('block.break', 4000, '$steve', 'Steve', 'world', '{\"block\":\"stone\"}')",
                    "INSERT INTO sessions (player_uuid, player_name, joined_at, left_at) VALUES ('$steve', 'Steve', 1000, 1500)",
                    "INSERT INTO sessions (player_uuid, player_name, joined_at, left_at, closed_by_restart) VALUES ('$steve', 'Steve', 2000, 4000, 1)",
                    "INSERT INTO audit (at, token, method, path, target, status, detail) VALUES (1000, 'admin', 'POST', '/api/v1/time', NULL, 200, NULL)",
                    "INSERT INTO experience (player_uuid, player_name, gained) VALUES ('$steve', 'Steve', 70)",
                    "INSERT INTO experience_baseline (player_uuid, xp_total, taken_at) VALUES ('$steve', 60, 1000)",
                ).forEach(statement::execute)
            }
        }
    }

    private fun <T> sqlite(db: Path, block: (Connection) -> T): T {
        Class.forName("org.sqlite.JDBC")
        return DriverManager.getConnection("jdbc:sqlite:${db.toAbsolutePath()}").use(block)
    }

    private fun tempFolder(): Path = Files.createTempDirectory("worldapi")
}
