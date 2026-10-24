package com.mairwunnx.worldapi.core

import com.mairwunnx.worldapi.api.ApiException
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.bukkit.plugin.Plugin
import java.util.concurrent.atomic.AtomicBoolean

/** Runs a block on the server thread and suspends the caller until it finishes. */
interface MainThread {
    suspend fun <T> call(block: () -> T): T

    companion object {
        /** For tests and code already on the right thread. */
        val Immediate = object : MainThread {
            override suspend fun <T> call(block: () -> T): T = block()
        }
    }
}

/**
 * Hops to the Bukkit main thread through the scheduler. A request never blocks the tick: it
 * waits off-thread, and if the tick loop doesn't pick the task up in time, the task is cancelled
 * before it can touch the world and the caller gets 503.
 */
class BukkitMainThread(private val plugin: Plugin, private val timeoutMillis: () -> Long) : MainThread {
    override suspend fun <T> call(block: () -> T): T {
        if (plugin.server.isPrimaryThread) return block()
        val result = CompletableDeferred<T>()
        val abandoned = AtomicBoolean(false)
        val task = try {
            plugin.server.scheduler.runTask(plugin, Runnable {
                if (abandoned.get()) return@Runnable
                try {
                    result.complete(block())
                } catch (error: Throwable) {
                    result.completeExceptionally(error)
                }
            })
        } catch (_: Exception) {
            throw ApiException(HttpStatusCode.ServiceUnavailable, "server_unavailable", "The server is shutting down")
        }
        try {
            return withTimeout(timeoutMillis()) { result.await() }
        } catch (_: TimeoutCancellationException) {
            abandoned.set(true)
            task.cancel()
            throw ApiException(HttpStatusCode.ServiceUnavailable, "server_busy", "The server thread didn't answer in time")
        } finally {
            if (!result.isCompleted) {
                abandoned.set(true)
                task.cancel()
            }
        }
    }
}
