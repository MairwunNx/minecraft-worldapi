package com.mairwunnx.worldapi.store

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * SQLite for event history, play sessions, the audit log, and experience gained. One connection, one thread:
 * writes queue up off the server thread, and reads take turns with them.
 */
class Store(file: Path) : AutoCloseable {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "WorldAPI store").apply { isDaemon = true } }
    private val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()
    private val connection: Connection

    init {
        // Paper ships the driver, but its class loader doesn't register it with DriverManager for plugins.
        Class.forName("org.sqlite.JDBC")
        connection = DriverManager.getConnection("jdbc:sqlite:${file.toAbsolutePath()}")
        connection.createStatement().use { statement ->
            statement.execute("PRAGMA journal_mode=WAL")
            statement.execute("PRAGMA synchronous=NORMAL")
            SCHEMA.forEach(statement::execute)
        }
    }

    /** Queues a write; the caller never waits for the disk. */
    fun write(block: Connection.() -> Unit) {
        if (executor.isShutdown) return
        executor.execute {
            runCatching { connection.block() }.onFailure { System.err.println("[WorldAPI] store write failed: $it") }
        }
    }

    suspend fun <T> read(block: Connection.() -> T): T = withContext(dispatcher) { connection.block() }

    /** Runs a block on the store thread and waits; for startup and shutdown only. */
    fun <T> blocking(block: Connection.() -> T): T = executor.submit<T> { connection.block() }.get()

    override fun close() {
        executor.shutdown()
        executor.awaitTermination(10, TimeUnit.SECONDS)
        connection.close()
    }

    private companion object {
        val SCHEMA = listOf(
            """CREATE TABLE IF NOT EXISTS events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                type TEXT NOT NULL,
                at INTEGER NOT NULL,
                player_uuid TEXT,
                player_name TEXT,
                world TEXT,
                data TEXT NOT NULL
            )""",
            "CREATE INDEX IF NOT EXISTS events_type_at ON events(type, at)",
            "CREATE INDEX IF NOT EXISTS events_player ON events(player_uuid, id)",
            """CREATE TABLE IF NOT EXISTS sessions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                player_uuid TEXT NOT NULL,
                player_name TEXT NOT NULL,
                joined_at INTEGER NOT NULL,
                left_at INTEGER,
                closed_by_restart INTEGER NOT NULL DEFAULT 0
            )""",
            "CREATE INDEX IF NOT EXISTS sessions_player ON sessions(player_uuid, id)",
            """CREATE TABLE IF NOT EXISTS audit (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                at INTEGER NOT NULL,
                token TEXT NOT NULL,
                method TEXT NOT NULL,
                path TEXT NOT NULL,
                target TEXT,
                status INTEGER NOT NULL,
                detail TEXT
            )""",
            """CREATE TABLE IF NOT EXISTS experience (
                player_uuid TEXT PRIMARY KEY,
                player_name TEXT NOT NULL,
                gained INTEGER NOT NULL
            )""",
        )
    }
}

fun PreparedStatement.bind(vararg values: Any?): PreparedStatement {
    values.forEachIndexed { index, value ->
        when (value) {
            null -> setObject(index + 1, null)
            is String -> setString(index + 1, value)
            is Long -> setLong(index + 1, value)
            is Int -> setInt(index + 1, value)
            is Boolean -> setInt(index + 1, if (value) 1 else 0)
            else -> error("Can't bind ${value::class}")
        }
    }
    return this
}

fun <T> ResultSet.rows(map: ResultSet.() -> T): List<T> = use { buildList { while (next()) add(map()) } }

fun ResultSet.longOrNull(column: String): Long? = getLong(column).takeUnless { wasNull() }
