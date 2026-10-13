package com.mairwunnx.worldapi.store

import com.mairwunnx.worldapi.config.DatabaseSettings
import com.mairwunnx.worldapi.config.DatabaseType
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.core.vendors.MariaDBDialect
import org.jetbrains.exposed.v1.core.vendors.MysqlDialect
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.vendors.currentDialectMetadata
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/**
 * The database for event history, play sessions, the audit log, and experience gained: SQLite,
 * PostgreSQL, or MySQL. Writes queue up on one thread off the server thread and keep their order;
 * reads run on a few threads of their own, after the writes queued before them.
 */
class Store(private val settings: DatabaseSettings, folder: Path) : AutoCloseable {
    // The pool comes first: it fails fast on a database it can't reach, before any thread starts.
    private val pool = HikariDataSource(hikari(settings, folder))
    private val writer: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "WorldAPI store").apply { isDaemon = true } }
    private val readers: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(settings.poolSize)
    private val database: Database

    /** The last queued write; a read waits for it, so it sees what its caller wrote before. */
    @Volatile
    private var lastWrite: CompletableFuture<Unit> = CompletableFuture.completedFuture(Unit)

    init {
        try {
            database = Database.connect(pool, databaseConfig = DatabaseConfig { explicitDialect = mysqlDialect() })
            blocking { createSchema() }
        } catch (error: Exception) {
            writer.shutdownNow()
            pool.close()
            throw error
        }
    }

    /** Where the data lives, for the log; never the password. */
    val description: String
        get() = when (settings.type) {
            DatabaseType.SQLITE -> "SQLite (${settings.file})"
            DatabaseType.POSTGRESQL -> "PostgreSQL at ${settings.host}:${port(settings)}/${settings.name}"
            DatabaseType.MYSQL -> "MySQL at ${settings.host}:${port(settings)}/${settings.name}"
        }

    /** Queues a write; the caller never waits for the database. */
    fun write(block: JdbcTransaction.() -> Unit) {
        synchronized(this) {
            if (writer.isShutdown) return
            lastWrite = try {
                CompletableFuture.supplyAsync({
                    runCatching { transaction(database) { block() } }.onFailure { System.err.println("[WorldAPI] store write failed: $it") }
                    Unit
                }, writer)
            } catch (_: RejectedExecutionException) {
                return
            }
        }
    }

    suspend fun <T> read(block: JdbcTransaction.() -> T): T {
        lastWrite.await()
        return withContext(readers) { transaction(database) { block() } }
    }

    /** Runs a block on the write thread and waits; for startup and shutdown only. */
    fun <T> blocking(block: JdbcTransaction.() -> T): T = writer.submit<T> { transaction(database) { block() } }.get()

    override fun close() {
        writer.shutdown()
        writer.awaitTermination(10, TimeUnit.SECONDS)
        TransactionManager.closeAndUnregister(database)
        pool.close()
    }

    /** Creates the tables a new database lacks, and the indexes an existing table lacks. */
    private fun JdbcTransaction.createSchema() {
        SchemaUtils.create(*TABLES)
        currentDialectMetadata.resetCaches()
        val existing = currentDialectMetadata.existingIndices(*TABLES)
        TABLES.forEach { table ->
            val names = existing[table].orEmpty().map { it.indexName.lowercase() }
            table.indices.filter { it.indexName.lowercase() !in names }.forEach { index -> SchemaUtils.createIndex(index).forEach(::exec) }
        }
    }

    /**
     * The MariaDB driver serves MySQL too, and Exposed would take any server it reaches for
     * MariaDB, so the server says which it is.
     */
    private fun mysqlDialect() = if (settings.type != DatabaseType.MYSQL) {
        null
    } else {
        val product = pool.connection.use { it.metaData.databaseProductName + " " + it.metaData.databaseProductVersion }
        if ("mariadb" in product.lowercase()) MariaDBDialect() else MysqlDialect()
    }

    private companion object {
        fun port(settings: DatabaseSettings) = settings.port ?: if (settings.type == DatabaseType.POSTGRESQL) 5432 else 3306

        fun hikari(settings: DatabaseSettings, folder: Path) = HikariConfig().apply {
            poolName = "WorldAPI"
            // The readers, plus one for the writer.
            maximumPoolSize = settings.poolSize + 1
            isAutoCommit = false
            // The class names come from class references, so they follow the drivers when the jar relocates them.
            when (settings.type) {
                DatabaseType.SQLITE -> {
                    // Paper ships this driver; it isn't in our jar.
                    driverClassName = org.sqlite.JDBC::class.java.name
                    jdbcUrl = "jdbc:sqlite:${folder.resolve(settings.file).toAbsolutePath()}"
                    addDataSourceProperty("journal_mode", "WAL")
                    addDataSourceProperty("synchronous", "NORMAL")
                    // Readers and the writer hold separate connections; one waits for the other instead of failing.
                    addDataSourceProperty("busy_timeout", "10000")
                }
                DatabaseType.POSTGRESQL -> {
                    driverClassName = org.postgresql.Driver::class.java.name
                    jdbcUrl = "jdbc:postgresql://${settings.host}:${port(settings)}/${settings.name}"
                }
                DatabaseType.MYSQL -> {
                    driverClassName = org.mariadb.jdbc.Driver::class.java.name
                    jdbcUrl = "jdbc:mariadb://${settings.host}:${port(settings)}/${settings.name}"
                }
            }
            if (settings.type != DatabaseType.SQLITE) {
                username = settings.user
                password = settings.password
            }
            settings.properties.forEach { (key, value) -> addDataSourceProperty(key, value) }
        }
    }
}
