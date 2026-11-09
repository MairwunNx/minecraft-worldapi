package com.mairwunnx.worldapi.metrics

import com.mairwunnx.worldapi.api.Usage
import com.mairwunnx.worldapi.config.DatabaseType
import org.bstats.bukkit.Metrics
import org.bstats.charts.SimplePie
import org.bstats.charts.SingleLineChart
import org.bukkit.plugin.java.JavaPlugin
import java.util.concurrent.Callable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Logger

/**
 * Anonymous usage counts on bstats.org: the database type, REST requests, and WebSocket connections. bStats sends
 * every 30 minutes from its own thread, so the charts read only [Usage]'s counters and a value fixed at startup.
 * Nothing here may stop WorldAPI: any failure is one warning in the log.
 */
class BStats private constructor(private val metrics: Metrics) : AutoCloseable {
    override fun close() {
        runCatching { metrics.shutdown() }
    }

    companion object {
        /** WorldAPI's id on bstats.org; 0 until it's registered, and then nothing starts. */
        const val BSTATS_ID = 0

        /** Starts bStats unless [enabled] is false or the id is unset; null when it didn't start. */
        fun start(plugin: JavaPlugin, enabled: Boolean, database: DatabaseType, usage: Usage): BStats? {
            if (!enabled || BSTATS_ID == 0) return null
            val soft = Soft(plugin.logger)
            return try {
                val metrics = Metrics(plugin, BSTATS_ID)
                // The name config.yml uses: sqlite, postgresql, or mysql. The store opens it once, so it holds until a restart.
                val type = database.name.lowercase()
                metrics.addCustomChart(SimplePie("database", soft.callable<String?>(null) { type }))
                metrics.addCustomChart(SingleLineChart("rest_requests", soft.callable(0) { Soft.clamp(usage.drainRequests()) }))
                metrics.addCustomChart(SingleLineChart("websocket_connections", soft.callable(0) { Soft.clamp(usage.drainSockets()) }))
                BStats(metrics)
            } catch (error: Throwable) {
                soft.warn(error)
                null
            }
        }
    }
}

/** Turns a failure into one warning, the first, so a broken chart neither spams the log nor reaches bStats. */
internal class Soft(private val logger: Logger) {
    private val warned = AtomicBoolean()

    fun warn(error: Throwable) {
        if (warned.compareAndSet(false, true)) logger.warning("bStats failed, and WorldAPI works on without it: $error")
    }

    /** A chart callback that answers [fallback] instead of throwing; bStats leaves out a null or a zero. */
    fun <T> callable(fallback: T, block: () -> T): Callable<T> = Callable {
        try {
            block()
        } catch (error: Throwable) {
            warn(error)
            fallback
        }
    }

    companion object {
        /** A line chart takes an Int; a count that big would only come from a bug. */
        fun clamp(value: Long): Int = value.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
    }
}
