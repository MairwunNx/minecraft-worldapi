package com.mairwunnx.worldapi

import com.mairwunnx.worldapi.api.HttpServer
import com.mairwunnx.worldapi.command.TokenCommands
import com.mairwunnx.worldapi.command.WorldApiCommand
import com.mairwunnx.worldapi.config.ConfigFile
import com.mairwunnx.worldapi.config.WorldApiConfig
import com.mairwunnx.worldapi.core.BukkitMainThread
import com.mairwunnx.worldapi.core.ConsoleAppender
import com.mairwunnx.worldapi.core.WorldApi
import com.mairwunnx.worldapi.events.ApiEvent
import com.mairwunnx.worldapi.events.EventBus
import com.mairwunnx.worldapi.events.Listeners
import com.mairwunnx.worldapi.events.Sampler
import com.mairwunnx.worldapi.events.Snapshots
import com.mairwunnx.worldapi.events.Topic
import com.mairwunnx.worldapi.events.Windows
import com.mairwunnx.worldapi.script.ScriptManager
import com.mairwunnx.worldapi.stats.Experience
import com.mairwunnx.worldapi.stats.PlayerData
import com.mairwunnx.worldapi.stats.Statistics
import com.mairwunnx.worldapi.store.History
import com.mairwunnx.worldapi.store.Store
import com.mairwunnx.worldapi.tokens.TokenMenu
import com.mairwunnx.worldapi.tokens.TokenStore
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
    private var stopConsole: (() -> Unit)? = null

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

        store = try {
            Store(loaded.database, dataFolder.toPath())
        } catch (error: Exception) {
            logger.severe("Couldn't open the database, so WorldAPI stays off: ${error.message}")
            server.pluginManager.disablePlugin(this)
            return
        }
        logger.info("Keeping history in ${store.description}")
        val history = History(store)
        history.lastActivity()?.let { history.closeStaleSessions(Instant.ofEpochMilli(it)) }
        val bus = EventBus(history) { config.get().events.chat }
        val main = BukkitMainThread(this) { config.get().http.mainThreadTimeoutMillis }
        val scripts = ScriptManager(this, bus, config::get, logger)
        val tokens = TokenStore(dataFolder.toPath().resolve("tokens.yml"))
        try {
            tokens.load()
        } catch (error: Exception) {
            logger.severe("tokens.yml is invalid, so in-game tokens don't work until it's fixed: ${error.message}")
        }
        api = WorldApi(server, main, history, bus, Statistics(server, main), Experience(store), scripts, tokens, config, pluginMeta.version)
        stopConsole = try {
            ConsoleAppender.install(api.console)
        } catch (error: Throwable) {
            logger.warning("Can't read the console, so GET /server/logs stays empty: $error")
            null
        }
        val menu = TokenMenu(this, tokens, { config.get().scripts.enabled }, logger)
        server.pluginManager.registerEvents(menu, this)

        hub = WebSocketHub(config::get, { token -> http?.authenticate(token) }, Snapshots(server, main))
        bus.subscribe(hub)
        bus.interest = hub::wants

        val windows = Windows(bus) { config.get().websocket.blockWindowSeconds }
        val listeners = Listeners(this, bus, history, api.experience, windows) { config.get().events.chat }
        server.pluginManager.registerEvents(listeners, this)
        val sampler = Sampler(server, bus, config::get)
        server.scheduler.runTaskTimer(this, Runnable {
            sampler.tick()
            windows.flushIfDue()
        }, 20, 5)
        server.scheduler.runTaskTimerAsynchronously(this, Runnable { api.experience.flush() }, 20 * 5, 20 * 5)
        seedExperience()
        // Once a day is plenty for pruning; it runs off the server thread anyway.
        server.scheduler.runTaskTimerAsynchronously(this, Runnable { history.prune(config.get().events.retentionDays) }, 20 * 60, 20 * 60 * 60 * 24)

        lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            event.registrar().register(WorldApiCommand.build(::reload, ::status, tokenCommands(menu)), "Reload WorldAPI, inspect it, or manage tokens")
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
        api.restart.close()
        hub.closeAll(if (server.isStopping) "Server stopping" else "WorldAPI disabled")
        stopConsole?.invoke()
        stopConsole = null
        http?.stop()
        http = null
        scope.cancel()
        api.experience.flush()
        store.close()
    }

    /**
     * Gives every player without an experience baseline one: online players from the live value,
     * everyone else from their playerdata file, read off the server thread.
     */
    private fun seedExperience() {
        server.onlinePlayers.forEach { api.experience.seed(it.uniqueId, it.name, it.totalExperience) }
        val folder = server.worlds.firstOrNull()?.worldFolder?.let(PlayerData::folder) ?: return
        server.scheduler.runTaskAsynchronously(this, Runnable {
            val names = server.offlinePlayers.associate { it.uniqueId to it.name }
            val seeded = api.experience.seedFromFiles(folder, names)
            if (seeded > 0) logger.info("Started the experience totals of $seeded players from their playerdata")
        })
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
        api.tokens.load()
        config.set(new)
        // The store keeps its connection; a new database takes a restart.
        val database = if (new.database != old.database) "; the database change applies after a restart" else ""
        if (http == null || HttpServer.changesNeedRestart(old, new)) {
            hub.closeAll("WorldAPI is restarting its HTTP server")
            http?.stop()
            startHttp()
            "WorldAPI reloaded and restarted its HTTP server$database"
        } else {
            "WorldAPI reloaded$database"
        }
    }

    private fun tokenCommands(menu: TokenMenu) = object : TokenCommands {
        private val namePattern = Regex("[A-Za-z0-9_-]{1,32}")

        override fun create(player: org.bukkit.entity.Player, name: String): String? {
            if (!namePattern.matches(name)) return "Names are 1–32 letters, digits, dashes, or underscores."
            if (config.get().auth.tokens.any { it.name == name } || api.tokens.tokens.any { it.name == name }) return "A token named $name already exists."
            menu.open(player, name)
            return null
        }

        override fun list(): List<String> {
            val now = Instant.now()
            val fromConfig = config.get().auth.tokens.map { "${it.name}: ${it.scopes.joinToString { s -> s.id }} (config.yml)" }
            val issued = api.tokens.tokens.map {
                val state = when {
                    it.expired(now) -> "expired ${it.expiresAt}"
                    it.expiresAt != null -> "expires ${it.expiresAt}"
                    else -> "never expires"
                }
                "${it.name}: ${it.scopes.joinToString { s -> s.id }} ($state, made by ${it.createdBy})"
            }
            return (fromConfig + issued).ifEmpty { listOf("No tokens yet. Create one with /worldapi token create <name>.") }
        }

        override fun revoke(name: String): Result<String> = runCatching {
            if (config.get().auth.tokens.any { it.name == name }) error("$name lives in config.yml; remove it there and run /worldapi reload.")
            if (!api.tokens.revoke(name)) error("No in-game token named $name.")
            logger.info("Token \"$name\" revoked")
            "Token $name revoked. Clients using it get 401 from now on."
        }

        override fun issuedNames() = api.tokens.tokens.map { it.name }
    }

    private fun status(): List<String> {
        val settings = config.get()
        return listOf(
            "WorldAPI ${pluginMeta.version}: " + if (http != null) "listening on ${settings.http.host}:${settings.http.port}${settings.http.basePath}" else "HTTP server is down",
            "Tokens: ${(settings.auth.tokens.map { it.name } + api.tokens.tokens.map { it.name }).joinToString().ifEmpty { "none" }}; anonymous reads " + if (settings.auth.anonymous.enabled) "on" else "off",
            "WebSocket clients: ${hub.size}",
            "Scripts: " + if (settings.scripts.enabled) "${api.scripts.list().size} loaded" else "off",
        )
    }
}
