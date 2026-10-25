package com.mairwunnx.worldapi.modcore

import com.mairwunnx.worldapi.admin.AdminAccounts
import com.mairwunnx.worldapi.admin.Icons
import com.mairwunnx.worldapi.api.HttpServer
import com.mairwunnx.worldapi.config.ConfigFile
import com.mairwunnx.worldapi.config.DatabaseSettings
import com.mairwunnx.worldapi.config.DatabaseType
import com.mairwunnx.worldapi.config.WorldApiConfig
import com.mairwunnx.worldapi.core.ConsoleAppender
import com.mairwunnx.worldapi.core.ModApi
import com.mairwunnx.worldapi.core.ModMainThread
import com.mairwunnx.worldapi.core.TickRate
import com.mairwunnx.worldapi.events.ApiEvent
import com.mairwunnx.worldapi.events.EventBus
import com.mairwunnx.worldapi.events.ModEvents
import com.mairwunnx.worldapi.events.ModSampler
import com.mairwunnx.worldapi.events.ModSnapshots
import com.mairwunnx.worldapi.events.Windows
import com.mairwunnx.worldapi.events.Topic
import com.mairwunnx.worldapi.events.Vanished
import com.mairwunnx.worldapi.expansion.WorldApiExpansions
import com.mairwunnx.worldapi.extensions.Expansions
import com.mairwunnx.worldapi.extensions.Extensions
import com.mairwunnx.worldapi.files.ServerFiles
import com.mairwunnx.worldapi.routes.Routes
import com.mairwunnx.worldapi.stats.Experience
import com.mairwunnx.worldapi.stats.ModStatistics
import com.mairwunnx.worldapi.stats.Tamed
import com.mairwunnx.worldapi.store.History
import com.mairwunnx.worldapi.store.Store
import com.mairwunnx.worldapi.tokens.TokenStore
import com.mairwunnx.worldapi.ws.WebSocketHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.storage.LevelResource
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Level
import java.util.logging.Logger

/**
 * WorldAPI for one server the game runs: a dedicated server for as long as it runs, or the server under a singleplayer
 * world from opening the world to leaving it. Everything it opens, it closes in [stop], so leaving a world frees the
 * port and the world's database before the next world opens.
 */
class Runtime private constructor(
    val server: MinecraftServer,
    val loader: Loader,
    private val logger: Logger,
    private val configPath: Path,
    val config: AtomicReference<WorldApiConfig>,
    private val store: Store,
    val api: ModApi,
    val hub: WebSocketHub,
) {
    private val windows = Windows(api.bus) { config.get().websocket.blockWindowSeconds }

    /** The game's moments as WorldAPI events; [Hooks] hands them in. */
    val events = ModEvents(server, loader, api.bus, api.history, api.experience, api.tamed, windows, api.vanished) { config.get().events.chat }

    /** State without events of its own, polled every tick. */
    val sampler = ModSampler(api, events, config::get)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    var http: HttpServer<ModApi>? = null
        private set

    /** Where the server listens, once it does. */
    var address: Pair<String, Int>? = null
        private set

    private var stopConsole: (() -> Unit)? = null
    private var removeDiscovery: (() -> Unit)? = null
    private var expansions: Expansions? = null
    private var ticks = 0L

    private fun start() {
        stopConsole = try {
            ConsoleAppender.install(api.console)
        } catch (error: Throwable) {
            logger.warning("Can't read the console, so GET /server/logs stays empty: $error")
            null
        }
        val host = Expansions(api.extensions, logger) { expansion -> ownerOf(expansion.javaClass) }
        expansions = host
        WorldApiExpansions.bind(host)
        startHttp()
        scope.launch {
            while (isActive) {
                delay(5_000)
                api.experience.flush()
            }
        }
        // Once a day is plenty for pruning; it runs off the server thread anyway.
        scope.launch {
            delay(60_000)
            while (isActive) {
                api.history.prune(config.get().events.retentionDays)
                delay(24 * 3_600_000L)
            }
        }
        seedExperience()
        seedPets()
        api.bus.publish(ApiEvent(Topic.SERVER_STATUS, buildJsonObject {
            put("status", "started")
            put("version", server.serverVersion)
        }))
    }

    /**
     * Gives every player without an experience baseline one: online players from the live value, everyone else from
     * their playerdata file, read off the server thread.
     */
    private fun seedExperience() {
        server.playerList.players.forEach { api.experience.seed(it.uuid, it.gameProfile.name(), it.totalExperience) }
        val folder = server.getWorldPath(LevelResource.PLAYER_DATA_DIR).toFile()
        if (!folder.isDirectory) return
        scope.launch {
            val names = com.mairwunnx.worldapi.routes.everyone(server).associate { it.uuid to it.name }
            val seeded = api.experience.seedFromFiles(folder, names)
            if (seeded > 0) logger.info("Started the experience totals of $seeded players from their playerdata")
        }
    }

    /** Counts the pets in the world once in its life: loaded mobs now, the entity files off the server thread. */
    private fun seedPets() {
        if (api.tamed.seeded) return
        val loaded = server.allLevels.flatMap { level -> level.allEntities.toList() }
            .mapNotNull { pet -> (pet as? net.minecraft.world.entity.OwnableEntity)?.ownerReference?.uuid?.takeIf { com.mairwunnx.worldapi.routes.tamed(pet) }?.let { pet.uuid to it } }.toMap()
        val roots = server.allLevels.map { net.minecraft.world.level.dimension.DimensionType.getStorageFolder(it.dimension(), server.getWorldPath(LevelResource.ROOT)).toFile() }
        scope.launch {
            val names = com.mairwunnx.worldapi.routes.everyone(server).mapNotNull { known -> known.name?.let { known.uuid to it } }.toMap()
            var skipped = 0
            val pets = HashMap<java.util.UUID, java.util.UUID>()
            com.mairwunnx.worldapi.stats.EntityFiles.folders(roots).forEach { folder -> com.mairwunnx.worldapi.stats.EntityFiles.pets(folder) { skipped += it }.forEach { pets[it.entity] = it.owner } }
            // Loaded mobs are newer than their files.
            pets.putAll(loaded)
            val owners = pets.values.groupingBy { it }.eachCount().mapNotNull { (owner, count) -> names[owner]?.let { owner to (it to count.toLong()) } }.toMap()
            api.tamed.seed(owners)
            logger.info("Counted ${pets.size} pets of ${owners.size} players for the tamed board" + if (skipped > 0) "; skipped $skipped chunks it couldn't read" else "")
        }
    }

    /** The mod a class came from, which owns an expansion's namespace; the game's own for anything unknown. */
    private fun ownerOf(type: Class<*>): Extensions.Owner {
        val id = loader.owner(type) ?: "worldapi"
        val mod = loader.mods().firstOrNull { it.id == id }
        return Extensions.Owner(mod?.name ?: id, id) { mod?.version ?: loader.version }
    }

    private fun startHttp() {
        val settings = config.get().http
        val endpoints = Routes.all
        val integrated = server.isSingleplayer
        // A singleplayer world listens on this machine only, and takes the next port when another game holds it.
        val host = if (integrated) "127.0.0.1" else settings.host
        // The game tests of both loaders run at once; each takes a port of its own.
        val port = System.getProperty("worldapi.port")?.toIntOrNull() ?: settings.port
        val ports = if (integrated) (port..minOf(port + 9, 65_535)).toList() else listOf(port)
        var last: Exception? = null
        for (port in ports) {
            val server = HttpServer(api, endpoints, hub, logger)
            try {
                server.start(host, port)
                http = server
                address = host to port
                logger.info("Listening on $host:$port${settings.basePath} with ${endpoints.size} endpoints")
                if (integrated) publish(host, port)
                return
            } catch (error: Exception) {
                server.stop()
                last = error
            }
        }
        http = null
        logger.log(Level.SEVERE, "Couldn't start the HTTP server on $host:${ports.joinToString("–") { it.toString() }.let { if (ports.size > 1) "${ports.first()}–${ports.last()}" else it }}", last)
    }

    /** Tells local clients, such as minecraft-mcp, where this world's WorldAPI is and how to sign in. */
    private fun publish(host: String, port: Int) {
        val token = config.get().auth.tokens.firstOrNull()?.token ?: return
        val url = "http://$host:$port${config.get().http.basePath}"
        removeDiscovery = Discovery.publish(Discovery.entry(url, token, server.worldData.levelName, loader.id, loader.version), port, logger)
    }

    /** At the end of every server tick. */
    fun tick() {
        api.ticks.tick()
        if (++ticks % 5 == 0L) api.vanished.refresh(server.playerList.players.filter(loader::vanished).mapTo(HashSet()) { it.uuid })
        sampler.tick()
        windows.flushIfDue()
    }

    /**
     * A singleplayer world's owner hears how to connect when they arrive: the whole message once per game, a short
     * line in every world after.
     */
    fun welcome(player: net.minecraft.server.level.ServerPlayer) {
        if (!WorldApiCommand.owner(player) || address == null) return
        if (welcomed) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal("WorldAPI is on for this world. ").withStyle(net.minecraft.ChatFormatting.GRAY)
                .append(net.minecraft.network.chat.Component.literal("[How to connect]").withStyle { it.withColor(net.minecraft.ChatFormatting.AQUA).withClickEvent(net.minecraft.network.chat.ClickEvent.RunCommand("/worldapi connect")) }))
        } else {
            welcomed = true
            WorldApiCommand.connect(player.createCommandSourceStack(), this)
        }
    }

    fun reload(): Result<String> = runCatching {
        val old = config.get()
        val new = ConfigFile.load(configPath).first
        api.tokens.load()
        config.set(new)
        val database = if (new.database != old.database) "; the database change applies after a restart" else ""
        if (http == null || HttpServer.changesNeedRestart(old, new)) {
            hub.closeAll("WorldAPI is restarting its HTTP server")
            http?.stop()
            removeDiscovery?.invoke()
            startHttp()
            "WorldAPI reloaded and restarted its HTTP server$database"
        } else {
            "WorldAPI reloaded$database"
        }
    }

    fun status(): List<String> {
        val settings = config.get()
        val at = address
        return listOf(
            "WorldAPI ${loader.version} on ${loader.software}: " + if (http != null && at != null) "listening on ${at.first}:${at.second}${settings.http.basePath}" else "HTTP server is down",
            "Tokens: ${(settings.auth.tokens.map { it.name } + api.tokens.tokens.map { it.name }).joinToString().ifEmpty { "none" }}; anonymous reads " + if (settings.auth.anonymous.enabled) "on" else "off",
            "WebSocket clients: ${hub.size}",
        )
    }

    fun stop() {
        api.bus.publish(ApiEvent(Topic.SERVER_STATUS, buildJsonObject { put("status", "stopping") }))
        server.playerList.players.forEach { api.history.closeSession(it.uuid.toString(), Instant.now()) }
        WorldApiExpansions.bind(null)
        api.restart.close()
        api.icons?.close()
        hub.closeAll(if (server.isSingleplayer) "The world closed" else "Server stopping")
        removeDiscovery?.invoke()
        removeDiscovery = null
        http?.stop()
        http = null
        scope.cancel()
        api.experience.flush()
        store.close()
        // Last: the pool logs its shutdown through the console it would copy into.
        stopConsole?.invoke()
        stopConsole = null
    }

    companion object {
        /** Whether this game showed the whole welcome already; it stays for the game's life, across worlds. */
        @Volatile
        private var welcomed = false

        /** Builds and starts WorldAPI for [server], or logs why it stays off and returns null. */
        fun start(server: MinecraftServer, loader: Loader, logger: Logger): Runtime? {
            val folder = loader.config.resolve("worldapi")
            val configPath = folder.resolve("config.yml")
            val (loaded, created) = try {
                ConfigFile.load(configPath)
            } catch (error: Exception) {
                logger.severe("config/worldapi/config.yml is invalid, so WorldAPI stays off: ${error.message}")
                return null
            }
            if (created) logger.info("Wrote config/worldapi/config.yml with a new admin token; read it from the file")
            val config = AtomicReference(loaded)
            val integrated = server.isSingleplayer
            // Each world keeps its own history and tokens in its save, so they travel with it.
            val data = server.getWorldPath(LevelResource.ROOT).resolve("worldapi").normalize()
            val database = if (integrated) DatabaseSettings(type = DatabaseType.SQLITE) else loaded.database
            val store = try {
                java.nio.file.Files.createDirectories(data)
                Store(database, data)
            } catch (error: Exception) {
                logger.severe("Couldn't open the database, so WorldAPI stays off: ${error.message}")
                return null
            }
            logger.info("Keeping history in ${store.description}" + if (integrated) " in this world's save" else "")
            val history = History(store)
            history.lastActivity()?.let { history.closeStaleSessions(Instant.ofEpochMilli(it)) }
            val vanished = Vanished()
            val bus = EventBus(history, vanished) { config.get().events.chat }
            val main = ModMainThread(server) { config.get().http.mainThreadTimeoutMillis }
            val tokens = TokenStore(data.resolve("tokens.yml"))
            try {
                tokens.load()
            } catch (error: Exception) {
                logger.severe("tokens.yml is invalid, so in-game tokens don't work until it's fixed: ${error.message}")
            }
            val root = server.serverDirectory.toAbsolutePath().normalize()
            val save = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize()
            // In singleplayer the game's folder holds other worlds and the launcher's files: only this world, the configs, and the logs.
            val reach = if (integrated) listOf(root.relativize(save).toString().replace('\\', '/'), "config", "logs") else null
            val files = ServerFiles(root, folder, folder.resolve("backups"), listOf(data), reach)
            lateinit var runtime: Runtime
            val api = ModApi(
                server, loader, main, history, bus, ModStatistics(server, main), Experience(store), tokens, config, vanished,
                Extensions(bus, main, logger), Tamed(store), AdminAccounts(store), Icons(folder.resolve("cache"), { server.serverVersion }, logger), files, TickRate(),
            )
            val hub = WebSocketHub(config::get, { token -> runtime.http?.authenticate(token) }, ModSnapshots(api))
            bus.subscribe(hub)
            bus.interest = hub::wants
            runtime = Runtime(server, loader, logger, configPath, config, store, api, hub)
            runtime.start()
            return runtime
        }
    }
}
