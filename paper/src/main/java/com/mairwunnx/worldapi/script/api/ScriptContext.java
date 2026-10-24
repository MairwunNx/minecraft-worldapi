package com.mairwunnx.worldapi.script.api;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import org.bukkit.Server;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.scheduler.BukkitTask;

/** What a script can do through WorldAPI. Everything registered here goes away when the script unloads. */
public interface ScriptContext {
    /** The script's id. */
    String id();

    Server server();

    /** Writes a line to the script log, readable over REST and the script.log WebSocket topic. */
    void log(String message);

    /** Handles an event at normal priority, skipping cancelled events. */
    default <T extends Event> void on(Class<T> type, Consumer<T> handler) {
        on(type, EventPriority.NORMAL, true, handler);
    }

    <T extends Event> void on(Class<T> type, EventPriority priority, boolean ignoreCancelled, Consumer<T> handler);

    /** Runs a task on the server thread after a delay in ticks. */
    BukkitTask later(long delayTicks, Runnable task);

    /** Runs a task on the server thread every periodTicks, starting after delayTicks. */
    BukkitTask every(long delayTicks, long periodTicks, Runnable task);

    /** Runs a task off the server thread. Don't touch the world from it; use {@link #sync}. */
    BukkitTask async(Runnable task);

    /** From an async task: runs a block on the server thread and waits for its result. */
    <T> T sync(Callable<T> block) throws Exception;

    /** Memory that lives as long as the script, for counters and state between events. */
    Map<String, Object> memory();
}
