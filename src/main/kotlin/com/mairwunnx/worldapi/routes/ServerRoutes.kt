package com.mairwunnx.worldapi.routes

import com.mairwunnx.worldapi.api.Scope
import com.mairwunnx.worldapi.api.badRequest
import com.mairwunnx.worldapi.api.endpoints
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
import com.mairwunnx.worldapi.model.SaveRequest
import com.mairwunnx.worldapi.model.SaveResult
import com.mairwunnx.worldapi.model.ServerInfo
import com.mairwunnx.worldapi.model.Tps
import com.mairwunnx.worldapi.model.WorldLoad
import io.papermc.paper.ServerBuildInfo
import net.kyori.adventure.text.Component
import net.kyori.adventure.title.Title
import org.bukkit.Server
import java.lang.management.ManagementFactory
import java.time.Duration
import java.time.Instant
import kotlin.math.round

val serverRoutes = endpoints("Server") {
    get("/server", "Server version, players, settings, and current load") {
        main { serverInfo(api.server, api.version) }
    }

    get("/server/performance", "TPS, tick times, memory, CPU, and load per world") {
        main { performance(api.server) }
    }

    get("/server/plugins", "Installed plugins") {
        main {
            api.server.pluginManager.plugins.sortedBy { it.name.lowercase() }.map {
                val meta = it.pluginMeta
                PluginInfo(meta.name, meta.version, it.isEnabled, meta.description, meta.authors, meta.website)
            }
        }
    }

    post<BroadcastRequest, Delivered>(
        "/server/broadcast", "Send a chat message, title, or action bar to everyone online", Scope.ADMIN,
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
        "/server/commands", "Run a console command and capture its output", Scope.ADMIN,
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

    post<SaveRequest, SaveResult>("/server/save", "Save every world, and player data", Scope.ADMIN) { request ->
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

private fun serverInfo(server: Server, version: String): ServerInfo {
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
        onlinePlayers = server.onlinePlayers.size,
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
    )
}

private const val MIB = 1024L * 1024

private fun Double.short() = round(this * 100) / 100

internal fun performance(server: Server): Performance {
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
        worlds = server.worlds.map { WorldLoad(it.name, it.chunkCount, it.entityCount, it.tileEntityCount, it.playerCount) },
        at = Instant.now().toString(),
    )
}
