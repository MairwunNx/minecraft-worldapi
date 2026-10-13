package com.mairwunnx.worldapi

import org.bukkit.Server
import java.lang.reflect.Proxy

/** A Server that answers every call with an empty default; enough for code that never touches it. */
fun fakeServer(): Server = Proxy.newProxyInstance(Server::class.java.classLoader, arrayOf(Server::class.java)) { _, method, _ ->
    when (method.returnType) {
        java.lang.Boolean.TYPE -> false
        Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Double.TYPE -> 0.0
        java.lang.Float.TYPE -> 0f
        String::class.java -> ""
        List::class.java, Collection::class.java -> emptyList<Any>()
        Set::class.java -> emptySet<Any>()
        else -> if (method.returnType.isArray) java.lang.reflect.Array.newInstance(method.returnType.componentType, 0) else null
    }
} as Server

fun fakePlugin(): org.bukkit.plugin.Plugin =
    Proxy.newProxyInstance(Server::class.java.classLoader, arrayOf(org.bukkit.plugin.Plugin::class.java)) { _, method, _ ->
        when (method.returnType) {
            java.lang.Boolean.TYPE -> false
            Server::class.java -> fakeServer()
            String::class.java -> "WorldAPI"
            else -> null
        }
    } as org.bukkit.plugin.Plugin

/** A WorldApi on fakes, with a real SQLite store in a temp folder. */
class TestApi(config: com.mairwunnx.worldapi.config.WorldApiConfig) : AutoCloseable {
    val ref = java.util.concurrent.atomic.AtomicReference(config)
    val store = sqliteStore(java.nio.file.Files.createTempFile("worldapi", ".db"))
    val history = com.mairwunnx.worldapi.store.History(store)
    val bus = com.mairwunnx.worldapi.events.EventBus(history) { ref.get().events.chat }
    val tokens = com.mairwunnx.worldapi.tokens.TokenStore(java.nio.file.Files.createTempDirectory("worldapi").resolve("tokens.yml"))
    val api = com.mairwunnx.worldapi.core.WorldApi(
        fakeServer(), com.mairwunnx.worldapi.core.MainThread.Immediate, history, bus,
        com.mairwunnx.worldapi.stats.Statistics(fakeServer(), com.mairwunnx.worldapi.core.MainThread.Immediate),
        com.mairwunnx.worldapi.stats.Experience(store),
        com.mairwunnx.worldapi.script.ScriptManager(fakePlugin(), bus, ref::get, java.util.logging.Logger.getAnonymousLogger()),
        tokens, ref, "test",
    )

    override fun close() = store.close()
}

/** A store on a SQLite file anywhere. */
fun sqliteStore(file: java.nio.file.Path) =
    com.mairwunnx.worldapi.store.Store(com.mairwunnx.worldapi.config.DatabaseSettings(file = file.toAbsolutePath().toString()), file.parent)
