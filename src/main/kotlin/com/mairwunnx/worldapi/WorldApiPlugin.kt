package com.mairwunnx.worldapi

import com.mairwunnx.worldapi.api.HttpServer
import com.mairwunnx.worldapi.command.WorldApiCommand
import com.mairwunnx.worldapi.config.ConfigFile
import com.mairwunnx.worldapi.config.WorldApiConfig
import com.mairwunnx.worldapi.core.BukkitMainThread
import com.mairwunnx.worldapi.core.WorldApi
import com.mairwunnx.worldapi.events.ApiEvent
import com.mairwunnx.worldapi.events.EventBus
import com.mairwunnx.worldapi.events.Listeners
import com.mairwunnx.worldapi.events.Sampler
import com.mairwunnx.worldapi.events.Snapshots
import com.mairwunnx.worldapi.events.Topic
import com.mairwunnx.worldapi.events.Windows
import com.mairwunnx.worldapi.script.ScriptManager
import com.mairwunnx.worldapi.stats.Statistics
import com.mairwunnx.worldapi.store.History
import com.mairwunnx.worldapi.store.Store
import com.mairwunnx.worldapi.ws.WebSocketHub
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.bukkit.plugin.java.JavaPlugin
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Level

class WorldApiPlugin : JavaPlugin() {
    private val config = AtomicReference<WorldApiConfig>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var store: Store
    private lateinit var api: WorldApi
    private lateinit var hub: WebSocketHub
    private var http: HttpServer? = null

    override fun onEnable() {
        val configPath = dataFolder.toPath().resolve("config.yml")
        val (loaded, created) = try {
            ConfigFile.load(configPath)
        } catch (error: Exception) {
            logger.severe("config.yml is invalid, so WorldAPI stays off: ${error.message}")
            server.pluginManager.disablePlugin(this)
            return
        }
        config.set(loaded)
        if (created) logger.info("Wrote config.yml with a new admin token; read it from the file")

        store = Store(dataFolder.toPath().resolve("worldapi.db"))
        val history = History(store)
        history.lastActivity()?.let { history.closeStaleSessions(Instant.ofEpochMilli(it)) }
        val bus = EventBus(history) { config.get().events.chat }
        val main = BukkitMainThread(this) { config.get().http.mainThreadTimeoutMillis }
        val scripts = ScriptManager(this, bus, config::get, logger)
        api = WorldApi(server, main, history, bus, Statistics(server, main), scripts, config, pluginMeta.version)

        hub = WebSocketHub(config::get, { token -> http?.authenticate(token) }, Snapshots(server, main))
        bus.subscribe(hub)
        bus.interest = hub::wants

        val windows = Windows(bus) { config.get().websocket.blockWindowSeconds }
        val listeners = Listeners(this, bus, history, windows) { config.get().events.chat }
        server.pluginManager.registerEvents(listeners, this)
        val sampler = Sampler(server, bus, config::get)
        server.scheduler.runTaskTimer(this, Runnable {
            sampler.tick()
            windows.flushIfDue()
        }, 20, 5)
        // Once a day is plenty for pruning; it runs off the server thread anyway.
        server.scheduler.runTaskTimerAsynchronously(this, Runnable { history.prune(config.get().events.retentionDays) }, 20 * 60, 20 * 60 * 60 * 24)

        lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            event.registrar().register(WorldApiCommand.build(::reload, ::status), "Reload or inspect WorldAPI")
        }

        startHttp()
        // The first tick means the worlds are loaded and the server is accepting players.
        server.scheduler.runTask(this, Runnable {
            bus.publish(ApiEvent(Topic.SERVER_STATUS, buildJsonObject {
                put("status", "started")
                put("version", server.minecraftVersion)
            }))
            scope.launch { scripts.loadSaved() }
        })
    }

    override fun onDisable() {
        if (!::api.isInitialized) return
        api.bus.publish(ApiEvent(Topic.SERVER_STATUS, buildJsonObject { put("status", if (server.isStopping) "stopping" else "plugin_disabled") }))
        server.onlinePlayers.forEach { api.history.closeSession(it.uniqueId.toString(), Instant.now()) }
        api.scripts.unloadAll()
        hub.closeAll(if (server.isStopping) "Server stopping" else "WorldAPI disabled")
        http?.stop()
        http = null
        scope.cancel()
        store.close()
    }

    private fun startHttp() {
        val settings = config.get().http
        val endpoints = Routes.all
        try {
            http = HttpServer(api, endpoints, hub, logger).also { it.start() }
            logger.info("Listening on ${settings.host}:${settings.port}${settings.basePath} with ${endpoints.size} endpoints")
        } catch (error: Exception) {
            http = null
            logger.log(Level.SEVERE, "Couldn't start the HTTP server on ${settings.host}:${settings.port}", error)
        }
    }

    private fun reload(): Result<String> = runCatching {
        val old = config.get()
        val new = ConfigFile.load(dataFolder.toPath().resolve("config.yml")).first
        config.set(new)
        if (http == null || HttpServer.changesNeedRestart(old, new)) {
            hub.closeAll("WorldAPI is restarting its HTTP server")
            http?.stop()
            startHttp()
            "WorldAPI reloaded and restarted its HTTP server"
        } else {
            "WorldAPI reloaded"
        }
    }

    private fun status(): List<String> {
        val settings = config.get()
        return listOf(
            "WorldAPI ${pluginMeta.version}: " + if (http != null) "listening on ${settings.http.host}:${settings.http.port}${settings.http.basePath}" else "HTTP server is down",
            "Tokens: ${settings.auth.tokens.joinToString { it.name }.ifEmpty { "none" }}; anonymous reads " + if (settings.auth.anonymous.enabled) "on" else "off",
            "WebSocket clients: ${hub.size}",
            "Scripts: " + if (settings.scripts.enabled) "${api.scripts.list().size} loaded" else "off",
        )
    }
}
