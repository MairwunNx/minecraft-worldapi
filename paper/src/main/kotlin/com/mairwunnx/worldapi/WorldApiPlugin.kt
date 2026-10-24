package com.mairwunnx.worldapi

import com.mairwunnx.worldapi.api.Scope
import com.mairwunnx.worldapi.api.HttpServer
import com.mairwunnx.worldapi.bukkit.Vanished
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
import com.mairwunnx.worldapi.expansion.WorldApiExpansions
import com.mairwunnx.worldapi.extensions.ExpansionPlugins
import com.mairwunnx.worldapi.extensions.Expansions
import com.mairwunnx.worldapi.extensions.Extensions
import com.mairwunnx.worldapi.plan.Plan
import com.mairwunnx.worldapi.script.ScriptManager
import com.mairwunnx.worldapi.stats.Experience
import com.mairwunnx.worldapi.stats.PlayerData
import com.mairwunnx.worldapi.stats.Statistics
import com.mairwunnx.worldapi.stats.Tamed
import com.mairwunnx.worldapi.stats.Taming
import com.mairwunnx.worldapi.store.History
import com.mairwunnx.worldapi.store.Store
import com.mairwunnx.worldapi.tokens.TokenMenu
import com.mairwunnx.worldapi.tokens.TokenStore
import com.mairwunnx.worldapi.ws.WebSocketHub
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Level
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.bukkit.plugin.java.JavaPlugin

class WorldApiPlugin : JavaPlugin() {
    private val config = AtomicReference<WorldApiConfig>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var store: Store
    private lateinit var api: WorldApi
    private lateinit var hub: WebSocketHub
    private var http: HttpServer? = null
    private var stopConsole: (() -> Unit)? = null
    private var plan: AutoCloseable? = null

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
        val vanished = Vanished()
        val bus = EventBus(history, vanished) { config.get().events.chat }
        val main = BukkitMainThread(this) { config.get().http.mainThreadTimeoutMillis }
        val scripts = ScriptManager(this, bus, config::get, logger)
        val tokens = TokenStore(dataFolder.toPath().resolve("tokens.yml"))
        try {
            tokens.load()
        } catch (error: Exception) {
            logger.severe("tokens.yml is invalid, so in-game tokens don't work until it's fixed: ${error.message}")
        }
        api = WorldApi(server, main, history, bus, Statistics(server, main), Experience(store), scripts, tokens, config, pluginMeta.version, vanished, Extensions(bus, logger), Tamed(store), com.mairwunnx.worldapi.admin.AdminAccounts(store),
            com.mairwunnx.worldapi.admin.Icons(dataFolder.toPath().resolve("cache"), { server.minecraftVersion }, logger),
            com.mairwunnx.worldapi.files.ServerFiles(java.nio.file.Path.of(""), dataFolder.toPath(), dataFolder.toPath().resolve("backups")))
        warnLegacy(loaded)
        stopConsole = try {
            ConsoleAppender.install(api.console)
        } catch (error: Throwable) {
            logger.warning("Can't read the console, so GET /server/logs stays empty: $error")
            null
        }
        val menu = TokenMenu(this, tokens, { config.get().auth.groups }, logger)
        server.pluginManager.registerEvents(menu, this)

        hub = WebSocketHub(config::get, { token -> http?.authenticate(token) }, Snapshots(server, main))
        bus.subscribe(hub)
        bus.interest = hub::wants

        val windows = Windows(bus) { config.get().websocket.blockWindowSeconds }
        val listeners = Listeners(this, bus, history, api.experience, windows, vanished) { config.get().events.chat }
        server.pluginManager.registerEvents(listeners, this)
        server.pluginManager.registerEvents(api.commands, this)
        val taming = Taming(this, api.tamed, logger)
        server.pluginManager.registerEvents(taming, this)
        // The expansion API: an expansion belongs to the plugin whose class loader loaded it.
        val expansions = Expansions(api.extensions, logger) { expansion ->
            server.pluginManager.plugins.firstOrNull { it.javaClass.classLoader === expansion.javaClass.classLoader } ?: this
        }
        WorldApiExpansions.bind(expansions)
        val expansionPlugins = ExpansionPlugins(this, expansions, logger)
        server.pluginManager.registerEvents(expansionPlugins, this)
        expansionPlugins.scanAll()
        val sampler = Sampler(server, bus, config::get)
        server.scheduler.runTaskTimer(this, Runnable {
            vanished.refresh(server.onlinePlayers)
            sampler.tick()
            windows.flushIfDue()
        }, 20, 5)
        server.scheduler.runTaskTimerAsynchronously(this, Runnable { api.experience.flush() }, 20 * 5, 20 * 5)
        seedExperience()
        // Once a day is plenty for pruning; it runs off the server thread anyway.
        server.scheduler.runTaskTimerAsynchronously(this, Runnable { history.prune(config.get().events.retentionDays) }, 20 * 60, 20 * 60 * 60 * 24)

        lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            val tokens = tokenCommands(menu)
            event.registrar().register(WorldApiCommand.build(::reload, ::status, tokens, hub(tokens)::open), "Open WorldAPI's menu, reload it, or manage tokens and panel codes")
        }

        startHttp()
        plan = Plan.start(this, api, { hub.size }, Routes.all.size)
        // The first tick means the worlds are loaded and the server is accepting players.
        server.scheduler.runTask(this, Runnable {
            taming.seed()
            bus.publish(ApiEvent(Topic.SERVER_STATUS, buildJsonObject {
                put("status", "started")
                put("version", server.minecraftVersion)
            }))
            scope.launch { scripts.loadSaved() }
        })
    }

    override fun onDisable() {
        if (!::api.isInitialized) return
        plan?.close()
        plan = null
        api.bus.publish(ApiEvent(Topic.SERVER_STATUS, buildJsonObject { put("status", if (server.isStopping) "stopping" else "plugin_disabled") }))
        server.onlinePlayers.forEach { api.history.closeSession(it.uniqueId.toString(), Instant.now()) }
        api.scripts.unloadAll()
        WorldApiExpansions.bind(null)
        api.restart.close()
        api.icons?.close()
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
        warnLegacy(new)
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

    private var hubMenu: com.mairwunnx.worldapi.command.HubMenu? = null

    /** The menu behind `/worldapi`, made once and registered for its clicks and chat prompts. */
    private fun hub(tokens: TokenCommands): com.mairwunnx.worldapi.command.HubMenu = hubMenu ?: com.mairwunnx.worldapi.command.HubMenu(this, object : com.mairwunnx.worldapi.command.HubActions {
        override fun status() = this@WorldApiPlugin.status()

        override fun tokens(): List<com.mairwunnx.worldapi.command.TokenLine> {
            val now = Instant.now()
            return config.get().auth.tokens.map { com.mairwunnx.worldapi.command.TokenLine(it.name, it.scopes, "From config.yml", issued = false) } +
                api.tokens.tokens.map {
                    val expiry = when {
                        it.expired(now) -> "Expired ${it.expiresAt!!.take(10)}"
                        it.expiresAt != null -> "Expires ${it.expiresAt!!.take(10)}"
                        else -> "Never expires"
                    }
                    com.mairwunnx.worldapi.command.TokenLine(it.name, it.scopes, "$expiry, made by ${it.createdBy}", issued = true)
                }
        }

        override fun accounts() = kotlinx.coroutines.runBlocking { api.accounts.list() }.map {
            val seen = it.lastLoginAt?.let { at -> "last sign-in " + Instant.ofEpochMilli(at).toString().take(10) } ?: "never signed in"
            com.mairwunnx.worldapi.command.AccountLine(it.name, it.groups, "Made by ${it.createdBy}, $seen")
        }

        override fun groups() = config.get().auth.let { auth -> auth.groups.keys.associateWith { group -> auth.resolver.resolve(listOf(group)).sortedBy { it.ordinal }.map { it.id } } }
        override fun reload() = this@WorldApiPlugin.reload()
        override fun adminCode(player: org.bukkit.entity.Player) { tokens.adminCode(player, null)?.let { player.sendMessage(net.kyori.adventure.text.Component.text(it, net.kyori.adventure.text.format.NamedTextColor.RED)) } }
        override fun createToken(player: org.bukkit.entity.Player, name: String) = tokens.create(player, name)
        override fun revokeToken(name: String) = tokens.revoke(name)
    }).also {
        hubMenu = it
        server.pluginManager.registerEvents(it, this)
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
            val fromConfig = config.get().auth.tokens.map { "${it.name}: ${it.scopes.joinToString()} (config.yml)" }
            val issued = api.tokens.tokens.map {
                val state = when {
                    it.expired(now) -> "expired ${it.expiresAt}"
                    it.expiresAt != null -> "expires ${it.expiresAt}"
                    else -> "never expires"
                }
                "${it.name}: ${it.scopes.joinToString()} ($state, made by ${it.createdBy})"
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

        override fun groupNames() = config.get().auth.groups.keys.toList()

        override fun adminCode(player: org.bukkit.entity.Player, group: String?): String? {
            val groups = config.get().auth.groups
            // A quick read on the server thread: the command needs the answer now, and SQLite answers in microseconds.
            val (accounts, existing) = kotlinx.coroutines.runBlocking { api.accounts.count() to api.accounts.find(player.name) }
            val chosen = group ?: if (accounts == 0L) "owner" else "admin"
            if (existing == null && chosen !in groups) return "No group $chosen in config.yml. Groups: ${groups.keys.joinToString()}"
            val code = if (existing != null) {
                api.accounts.issueCode(player.name, player.uniqueId.toString(), existing.groups, player.name, reset = true)
            } else {
                api.accounts.issueCode(player.name, player.uniqueId.toString(), listOf(chosen), player.name)
            }
            logger.info("${player.name} got an admin panel code" + if (existing != null) " to reset their password" else " for an account in group $chosen")
            player.sendMessage(net.kyori.adventure.text.Component.text(
                if (existing != null) "A code to set a new password for your panel account ${player.name}, good for 30 minutes:"
                else "A code for a panel account ${player.name} in group $chosen, good for 30 minutes:",
                net.kyori.adventure.text.format.NamedTextColor.GREEN,
            ))
            player.sendMessage(
                net.kyori.adventure.text.Component.text("[$code]", net.kyori.adventure.text.format.NamedTextColor.AQUA, net.kyori.adventure.text.format.TextDecoration.BOLD)
                    .clickEvent(net.kyori.adventure.text.event.ClickEvent.copyToClipboard(code))
                    .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(net.kyori.adventure.text.Component.text("Copies the code"))),
            )
            val url = config.get().panel.publicUrl.trim().trimEnd('/')
            if (url.isNotEmpty()) {
                player.sendMessage(
                    net.kyori.adventure.text.Component.text("[Open the admin panel]", net.kyori.adventure.text.format.NamedTextColor.GREEN, net.kyori.adventure.text.format.TextDecoration.BOLD)
                        .clickEvent(net.kyori.adventure.text.event.ClickEvent.openUrl("$url/admin/?code=$code"))
                        .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(net.kyori.adventure.text.Component.text("$url/admin/, with the code filled in"))),
                )
                player.sendMessage(net.kyori.adventure.text.Component.text("Then pick a password of at least 10 characters.", net.kyori.adventure.text.format.NamedTextColor.GRAY))
            } else {
                player.sendMessage(net.kyori.adventure.text.Component.text("Open WorldAPI's /admin page, choose \"I have a code\", and enter it with a password. " +
                    "Set panel.publicUrl in config.yml, and this message brings a link.", net.kyori.adventure.text.format.NamedTextColor.GRAY))
            }
            return null
        }
    }

    /** 2.x scope names still work, with the access they had; the log says what replaces them. */
    private fun warnLegacy(settings: com.mairwunnx.worldapi.config.WorldApiConfig) {
        val old = (settings.auth.tokens.map { it.name to it.scopes } + api.tokens.tokens.map { it.name to it.scopes })
            .filter { (_, scopes) -> scopes.any { it in Scope.legacy && it !in settings.auth.groups } }
            .map { it.first }
        if (old.isNotEmpty()) {
            logger.warning("Tokens ${old.joinToString()} use 2.x scopes (read, write:world, write:players); they keep working. " +
                "Their 3.0 names: read is the viewer group, write:world is world:write, write:players is players:write.")
        }
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
