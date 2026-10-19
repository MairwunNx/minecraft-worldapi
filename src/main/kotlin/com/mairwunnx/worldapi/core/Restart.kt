package com.mairwunnx.worldapi.core

import com.mairwunnx.worldapi.api.ApiException
import com.mairwunnx.worldapi.model.RestartStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Server
import java.time.Instant

/**
 * A restart or stop with a countdown in chat: it warns players at set marks, saves everything, and stops
 * the server. Whatever runs the server (Docker's restart policy, a start script loop) brings it
 * back. One restart at a time; scheduling another replaces it.
 */
class Restart(
    private val server: Server,
    private val main: MainThread,
    private val scope: CoroutineScope,
    private val secondMillis: Long = 1_000,
) {
    private class Pending(val job: Job, val at: Instant, val message: String?, val stop: Boolean)

    @Volatile
    private var pending: Pending? = null

    /** With [stop], players hear that the server stops rather than restarts; what runs it decides whether it comes back. */
    fun schedule(seconds: Int, message: String?, stop: Boolean = false): RestartStatus = synchronized(this) {
        val verb = if (stop) "stops" else "restarts"
        pending?.job?.cancel()
        val at = Instant.now().plusSeconds(seconds.toLong())
        val job = scope.launch {
            for (left in seconds downTo 1) {
                if (left == seconds || left in MARKS) say("Server $verb in ${duration(left)}" + (message?.let { ": $it" } ?: ""))
                delay(secondMillis)
            }
            // A busy tick only delays the restart; it never calls it off.
            while (true) {
                try {
                    main.call {
                        server.broadcast(Component.text(if (stop) "Server stopping now" else "Server restarting now", NamedTextColor.GOLD))
                        server.savePlayers()
                        server.worlds.forEach { it.save() }
                        server.shutdown()
                    }
                    break
                } catch (_: ApiException) {
                    delay(secondMillis)
                }
            }
        }
        pending = Pending(job, at, message, stop)
        status()
    }

    /** Stops a pending restart and tells the players; false when there was none. */
    suspend fun cancel(): Boolean {
        val stopped = synchronized(this) { pending?.also { pending = null } } ?: return false
        if (!stopped.job.isActive) return false
        stopped.job.cancel()
        say(if (stopped.stop) "Stop cancelled" else "Restart cancelled")
        return true
    }

    /** Drops a pending restart without a word, as when WorldAPI itself shuts down. */
    fun close() {
        synchronized(this) { pending?.job?.cancel(); pending = null }
    }

    fun status(): RestartStatus {
        val current = pending?.takeIf { it.job.isActive } ?: return RestartStatus(scheduled = false)
        val left = (current.at.toEpochMilli() - System.currentTimeMillis()).coerceAtLeast(0) / 1000
        return RestartStatus(scheduled = true, at = current.at.toString(), secondsLeft = left, message = current.message, kind = if (current.stop) "stop" else "restart")
    }

    /** A warning lost to a busy tick isn't worth stopping the countdown for. */
    private suspend fun say(text: String) {
        try {
            main.call { server.broadcast(Component.text(text, NamedTextColor.GOLD)) }
        } catch (_: ApiException) {
        }
    }

    companion object {
        private val MARKS = setOf(3600, 1800, 900, 600, 300, 120, 60, 30, 10, 5, 4, 3, 2, 1)

        internal fun duration(seconds: Int): String = when {
            seconds >= 60 && seconds % 60 == 0 -> (seconds / 60).let { if (it == 1) "1 minute" else "$it minutes" }
            seconds == 1 -> "1 second"
            else -> "$seconds seconds"
        }
    }
}
