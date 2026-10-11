package com.mairwunnx.worldapi.script

import com.mairwunnx.worldapi.script.api.ScriptContext
import org.bukkit.Server
import org.bukkit.event.Event
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.function.Consumer

/**
 * Tracks everything a script registers, so unloading can take it all back. A handler that
 * throws is logged to the script log and doesn't reach Bukkit.
 */
class ScriptContextImpl(
    private val id: String,
    private val plugin: Plugin,
    private val logger: (String) -> Unit,
) : ScriptContext {
    private val listener = object : Listener {}
    private val tasks = CopyOnWriteArrayList<BukkitTask>()
    private val memory = ConcurrentHashMap<String, Any>()

    @Volatile
    var closed = false
        private set

    val listenerCount get() = HandlerList.getRegisteredListeners(plugin).count { it.listener === listener }
    val taskCount get() = tasks.count { plugin.server.scheduler.isQueued(it.taskId) || plugin.server.scheduler.isCurrentlyRunning(it.taskId) }

    override fun id() = id

    override fun server(): Server = plugin.server

    override fun log(message: String) = logger(message)

    override fun <T : Event> on(type: Class<T>, priority: EventPriority, ignoreCancelled: Boolean, handler: Consumer<T>) {
        ensureOpen()
        plugin.server.pluginManager.registerEvent(type, listener, priority, { _, event ->
            if (closed || !type.isInstance(event)) return@registerEvent
            try {
                handler.accept(type.cast(event))
            } catch (error: Throwable) {
                log("error in ${type.simpleName} handler: $error")
            }
        }, plugin, ignoreCancelled)
    }

    override fun later(delayTicks: Long, task: Runnable): BukkitTask = track(plugin.server.scheduler.runTaskLater(plugin, guarded(task), delayTicks))

    override fun every(delayTicks: Long, periodTicks: Long, task: Runnable): BukkitTask {
        require(periodTicks >= 1) { "periodTicks must be at least 1" }
        return track(plugin.server.scheduler.runTaskTimer(plugin, guarded(task), delayTicks, periodTicks))
    }

    override fun async(task: Runnable): BukkitTask = track(plugin.server.scheduler.runTaskAsynchronously(plugin, guarded(task)))

    override fun <T> sync(block: Callable<T>): T {
        if (plugin.server.isPrimaryThread) return block.call()
        return plugin.server.scheduler.callSyncMethod(plugin, block).get(30, TimeUnit.SECONDS)
    }

    override fun memory(): MutableMap<String, Any> = memory

    private fun guarded(task: Runnable) = Runnable {
        if (closed) return@Runnable
        try {
            task.run()
        } catch (error: Throwable) {
            log("error in task: $error")
        }
    }

    private fun track(task: BukkitTask): BukkitTask {
        if (closed) task.cancel()
        tasks += task
        return task
    }

    private fun ensureOpen() = check(!closed) { "The script is unloaded" }

    /** Removes the script's listeners and cancels its tasks. */
    fun close() {
        closed = true
        HandlerList.unregisterAll(listener)
        tasks.forEach { it.cancel() }
        tasks.clear()
        memory.clear()
    }
}
