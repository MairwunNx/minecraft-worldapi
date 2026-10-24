package com.mairwunnx.worldapi.core

import com.mairwunnx.worldapi.api.ApiException
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import net.minecraft.server.MinecraftServer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hops to the game's server thread through its task queue, as [BukkitMainThread] does on Paper. A request never blocks
 * the tick: it waits off-thread, and if the server doesn't pick the task up in time, the task is dropped before it can
 * touch the world and the caller gets 503. A paused singleplayer game still runs its queue.
 */
class ModMainThread(private val server: MinecraftServer, private val timeoutMillis: () -> Long) : MainThread {
    override fun execute(block: () -> Unit) {
        if (server.isSameThread) block() else server.execute(block)
    }

    override suspend fun <T> call(block: () -> T): T {
        if (server.isSameThread) return block()
        if (!server.isRunning) throw ApiException(HttpStatusCode.ServiceUnavailable, "server_unavailable", "The server is shutting down")
        val result = CompletableDeferred<T>()
        val abandoned = AtomicBoolean(false)
        try {
            server.execute {
                if (abandoned.get()) return@execute
                try {
                    result.complete(block())
                } catch (error: Throwable) {
                    result.completeExceptionally(error)
                }
            }
        } catch (_: Exception) {
            throw ApiException(HttpStatusCode.ServiceUnavailable, "server_unavailable", "The server is shutting down")
        }
        try {
            return withTimeout(timeoutMillis()) { result.await() }
        } catch (_: TimeoutCancellationException) {
            throw ApiException(HttpStatusCode.ServiceUnavailable, "server_busy", "The server thread didn't answer in time")
        } finally {
            if (!result.isCompleted) abandoned.set(true)
        }
    }
}
