package com.mairwunnx.worldapi.routes

import com.mairwunnx.worldapi.api.Scope
import com.mairwunnx.worldapi.api.badRequest
import com.mairwunnx.worldapi.api.endpoints
import com.mairwunnx.worldapi.api.integer
import com.mairwunnx.worldapi.api.string
import com.mairwunnx.worldapi.core.ConsoleLog
import com.mairwunnx.worldapi.core.SystemInfo
import com.mairwunnx.worldapi.model.DatapackView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.mairwunnx.worldapi.bukkit.plain
import com.mairwunnx.worldapi.bukkit.text
import com.mairwunnx.worldapi.model.BroadcastRequest
import com.mairwunnx.worldapi.model.CommandRequest
import com.mairwunnx.worldapi.model.CommandResult
import com.mairwunnx.worldapi.model.Cpu
import com.mairwunnx.worldapi.model.Delivered
import com.mairwunnx.worldapi.model.Memory
import com.mairwunnx.worldapi.model.MessageKind
import com.mairwunnx.worldapi.model.Mspt
import com.mairwunnx.worldapi.model.Performance
import com.mairwunnx.worldapi.model.PluginInfo
import com.mairwunnx.worldapi.model.RestartRequest
import com.mairwunnx.worldapi.model.RestartStatus
import com.mairwunnx.worldapi.model.SaveRequest
import com.mairwunnx.worldapi.model.SaveResult
import com.mairwunnx.worldapi.model.ServerInfo
import com.mairwunnx.worldapi.model.ServerLog
import com.mairwunnx.worldapi.model.Tps
import com.mairwunnx.worldapi.model.WorldLoad
import io.papermc.paper.ServerBuildInfo
import net.kyori.adventure.text.Component
import net.kyori.adventure.title.Title
import com.mairwunnx.worldapi.bukkit.isVanished
import org.bukkit.Server
import java.lang.management.ManagementFactory
import java.time.Duration
import java.time.Instant
import kotlin.math.round

val serverRoutes = endpoints("Server", Scope.SERVER_READ) {
    get("/server", "Server version, players, settings, and current load") {
        val vanished = seesVanished
        main { serverInfo(api.server, api.version, vanished) }
    }

    get("/server/performance", "TPS, tick times, memory, CPU, and load per world, with the most common entity types") {
        val vanished = seesVanished
        main { performance(api.server, entityTypes = true, includeVanished = vanished) }.copy(jvm = SystemInfo.jvm())
    }

    get(
        "/server/system", "The host: CPU load, memory, swap, disk, container limits, open files, and the Java runtime",
        scope = Scope.SYSTEM_READ,
        description = "Load averages, MemAvailable, swap, resident memory, and cgroup limits come from Linux; elsewhere those fields are absent.",
    ) {
        withContext(Dispatchers.IO) { SystemInfo.system(api.server.worldContainer) }
    }

    get("/server/plugins", "Installed plugins") {
        main {
            api.server.pluginManager.plugins.sortedBy { it.name.lowercase() }.map {
                val meta = it.pluginMeta
                PluginInfo(meta.name, meta.version, it.isEnabled, meta.description, meta.authors, meta.website, relative(api.server.worldContainer, it.dataFolder), configFiles(api.server.worldContainer, it.dataFolder))
            }
        }
    }

    get(
        "/server/logs", "Recent console lines", scope = Scope.LOGS_READ,
        description = "What the console printed since WorldAPI started, up to limits.consoleLines, oldest first. " +
            "Colors are stripped, and WorldAPI's tokens never appear. Lines hold text players wrote (chat, names, commands), so read them as data.",
        query = listOf(
            string("level", "Only this level and above", values = ConsoleLog.LEVELS),
            string("grep", "Only lines that contain this text, ignoring case"),
            integer("limit", "How many of the newest matching lines to return (100 by default, at most limits.consoleLines)"),
        ),
    ) {
        val level = query("level")?.lowercase()?.also { if (it !in ConsoleLog.LEVELS) badRequest("level must be one of ${ConsoleLog.LEVELS.joinToString()}") }
        val limit = int("limit") ?: 100
        if (limit < 1) badRequest("limit must be at least 1")
        ServerLog(api.console.read(level, query("grep"), limit), api.console.size)
    }

    post<BroadcastRequest, Delivered>(
        "/server/broadcast", "Send a chat message, title, or action bar to everyone online", Scope.PLAYERS_WRITE,
    ) { request ->
        val limit = config.limits.maxMessageLength
        val message = text(request.message, request.format, limit)
        val subtitle = request.subtitle?.let { text(it, request.format, limit) }
        auditDetail = request.message
        main {
            val players = api.server.onlinePlayers
            when (request.kind) {
                MessageKind.CHAT -> api.server.broadcast(message)
                MessageKind.ACTIONBAR -> players.forEach { it.sendActionBar(message) }
                MessageKind.TITLE -> {
                    val title = Title.title(message, subtitle ?: Component.empty(), times(request.fadeInMillis, request.stayMillis, request.fadeOutMillis))
                    players.forEach { it.showTitle(title) }
                }
            }
            Delivered(players.size)
        }
    }

    post<CommandRequest, CommandResult>(
        "/server/commands", "Run a console command and capture its output", Scope.SERVER_CONSOLE,
        description = "Runs as the console. Every call goes to the audit log with the full command.",
    ) { request ->
        val command = request.command.trim().removePrefix("/")
        if (command.isEmpty()) badRequest("command is empty")
        if (command.length > 32_767) badRequest("command is too long")
        auditTarget = command.substringBefore(' ')
        auditDetail = command
        main {
            val output = mutableListOf<String>()
            val sender = api.server.createCommandSender { output += it.plain() }
            // A command with bad syntax is the caller's mistake, not a server error: report it as output.
            val success = try {
                api.server.dispatchCommand(sender, command)
            } catch (error: org.bukkit.command.CommandException) {
                output += generateSequence<Throwable>(error) { it.cause }.last().message ?: "The command failed"
                false
            }
            CommandResult(success, output)
        }
    }

    for (stop in listOf(false, true)) {
        val word = if (stop) "stop" else "restart"
        post<RestartRequest, RestartStatus>(
            "/server/$word", if (stop) "Stop the server after a countdown in chat" else "Restart the server after a countdown in chat", Scope.SERVER_CONTROL,
            description = "Warns players in chat at set marks (a minute, 30 seconds, 10, 5 to 1), saves every world and player, then stops the server. " +
                (if (stop) "Whether it stays down depends on what runs it: Docker's `restart: unless-stopped` or a start script loop brings it back, and then a stop is a restart. "
                else "Whatever runs the server must start it again, such as Docker's restart policy or a start script loop. ") +
                "One restart or stop is pending at a time: scheduling either replaces it; DELETE calls it off.",
        ) { request ->
            if (request.countdownSeconds !in 0..3_600) badRequest("countdownSeconds must be between 0 and 3600")
            val message = request.message?.trim()?.takeIf { it.isNotEmpty() }
            if (message != null && message.length > config.limits.maxMessageLength) badRequest("message is longer than ${config.limits.maxMessageLength} characters")
            auditTarget = "$word in ${request.countdownSeconds} s"
            auditDetail = message
            api.restart.schedule(request.countdownSeconds, message, stop)
        }

        get("/server/$word", "Whether a restart or stop is pending, and when", scope = Scope.SERVER_READ) {
            api.restart.status()
        }

        delete("/server/$word", "Call off a pending restart or stop", Scope.SERVER_CONTROL) {
            RestartStatus(scheduled = false, cancelled = api.restart.cancel())
        }
    }

    post<SaveRequest, SaveResult>("/server/save", "Save every world, and player data", Scope.SERVER_CONTROL) { request ->
        main {
            if (request.players) api.server.savePlayers()
            val worlds = api.server.worlds.onEach { it.save() }.map { it.name }
            SaveResult(worlds, request.players)
        }
    }
}

internal fun times(fadeIn: Long?, stay: Long?, fadeOut: Long?): Title.Times = Title.Times.times(
    Duration.ofMillis((fadeIn ?: 500).coerceIn(0, 60_000)),
    Duration.ofMillis((stay ?: 3_500).coerceIn(0, 600_000)),
    Duration.ofMillis((fadeOut ?: 1_000).coerceIn(0, 60_000)),
)

private fun serverInfo(server: Server, version: String, includeVanished: Boolean): ServerInfo {
    val build = ServerBuildInfo.buildInfo()
    val started = ManagementFactory.getRuntimeMXBean().startTime
    val main = server.worlds.first()
    val tps = server.tps.map { minOf(it, 20.0).short() }
    val runtime = Runtime.getRuntime()
    return ServerInfo(
        name = server.name,
        brand = build.brandName(),
        minecraftVersion = build.minecraftVersionName(),
        build = build.buildNumber().takeIf { it.isPresent }?.asInt,
        buildTime = build.buildTime().toString(),
        apiVersion = server.bukkitVersion,
        worldApiVersion = version,
        motd = server.motd().plain(),
        maxPlayers = server.maxPlayers,
        onlinePlayers = server.onlinePlayers.count { includeVanished || !it.isVanished() },
        startedAt = Instant.ofEpochMilli(started).toString(),
        uptimeSeconds = (System.currentTimeMillis() - started) / 1000,
        defaultGameMode = server.defaultGameMode.name.lowercase(),
        difficulty = main.difficulty.name.lowercase(),
        hardcore = server.isHardcore,
        onlineMode = server.onlineMode,
        whitelist = server.hasWhitelist(),
        viewDistance = server.viewDistance,
        simulationDistance = server.simulationDistance,
        worlds = server.worlds.map { it.name },
        currentTick = server.currentTick,
        tickRate = server.serverTickManager.tickRate,
        frozen = server.serverTickManager.isFrozen,
        tps = tps[0],
        tps5m = tps[1],
        tps15m = tps[2],
        mspt = server.averageTickTime.short(),
        memoryUsedMb = (runtime.totalMemory() - runtime.freeMemory()) / MIB,
        memoryMaxMb = runtime.maxMemory() / MIB,
        loadedChunks = server.worlds.sumOf { it.chunkCount },
        entities = server.worlds.sumOf { it.entityCount },
        datapacks = server.datapackManager.packs.sortedBy { it.name }.map {
            DatapackView(it.name, it.title.plain(), it.description.plain().takeIf(String::isNotBlank), it.isEnabled, it.isRequired, it.source.toString().lowercase())
        },
    )
}

private const val MIB = 1024L * 1024

private val CONFIG_EXTENSIONS = setOf("yml", "yaml", "json", "properties", "toml", "conf")

private fun relative(root: java.io.File, file: java.io.File): String? =
    runCatching { root.canonicalFile.toPath().relativize(file.canonicalFile.toPath()).toString().replace('\\', '/') }.getOrNull()?.takeUnless { it.startsWith("..") }

/** A plugin's config files, two folders deep at most; big data folders (BlueMap's tiles) stay out of reach. */
private fun configFiles(root: java.io.File, folder: java.io.File): List<String> {
    if (!folder.isDirectory) return emptyList()
    return folder.walkTopDown().maxDepth(3).filter { it.isFile && it.extension.lowercase() in CONFIG_EXTENSIONS }
        .take(50).mapNotNull { relative(root, it) }.sorted().toList()
}

/** Counts keys and keeps the [top] most common, most first; ties go alphabetically. */
internal fun topTypes(keys: List<String>, top: Int = 10): Map<String, Int> =
    keys.groupingBy { it }.eachCount().entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .take(top)
        .associateTo(LinkedHashMap()) { it.key to it.value }

private fun Double.short() = round(this * 100) / 100

/** With [entityTypes], each world also counts its entities by type, which walks every entity once. */
/** Player counts leave vanished players out unless [includeVanished]; the streamed topic never includes them. */
internal fun performance(server: Server, entityTypes: Boolean = false, includeVanished: Boolean = false): Performance {
    val tps = server.tps.map { minOf(it, 20.0).short() }
    val ticks = server.tickTimes.map { it / 1_000_000.0 }.sorted()
    fun percentile(p: Double) = if (ticks.isEmpty()) 0.0 else ticks[((ticks.size - 1) * p).toInt()].short()
    val runtime = Runtime.getRuntime()
    val os = ManagementFactory.getOperatingSystemMXBean()
    val sun = os as? com.sun.management.OperatingSystemMXBean
    return Performance(
        tps = Tps(tps[0], tps[1], tps[2]),
        mspt = Mspt(server.averageTickTime.short(), percentile(0.5), percentile(0.95), percentile(0.99), ticks.lastOrNull()?.short() ?: 0.0),
        memory = Memory(runtime.totalMemory() - runtime.freeMemory(), runtime.totalMemory(), runtime.maxMemory()),
        cpu = Cpu(sun?.processCpuLoad?.takeIf { it >= 0 }?.short(), sun?.cpuLoad?.takeIf { it >= 0 }?.short(), os.availableProcessors),
        worlds = server.worlds.map {
            WorldLoad(it.name, it.chunkCount, it.entityCount, it.tileEntityCount, if (includeVanished) it.playerCount else it.players.count { p -> !p.isVanished() }, if (entityTypes) topTypes(it.entities.map { e -> e.type.key.asString() }) else null)
        },
        at = Instant.now().toString(),
    )
}
