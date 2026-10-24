package com.mairwunnx.worldapi.core

import com.mairwunnx.worldapi.admin.AdminAccounts
import com.mairwunnx.worldapi.admin.Icons
import com.mairwunnx.worldapi.commands.ModCommands
import com.mairwunnx.worldapi.config.WorldApiConfig
import com.mairwunnx.worldapi.events.EventBus
import com.mairwunnx.worldapi.events.Vanished
import com.mairwunnx.worldapi.extensions.Extensions
import com.mairwunnx.worldapi.files.LineYaml
import com.mairwunnx.worldapi.files.ModLists
import com.mairwunnx.worldapi.files.ServerFiles
import com.mairwunnx.worldapi.modcore.Loader
import com.mairwunnx.worldapi.script.Scripts
import com.mairwunnx.worldapi.stats.Experience
import com.mairwunnx.worldapi.stats.ModStatistics
import com.mairwunnx.worldapi.stats.Tamed
import com.mairwunnx.worldapi.store.History
import com.mairwunnx.worldapi.tokens.TokenStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.Level
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.name

/**
 * [WorldApi] on Fabric and NeoForge: the shared services plus the game's server, for a dedicated server or the one
 * under a singleplayer world. One lives as long as its server; a singleplayer game makes a new one for every world.
 */
class ModApi(
    val server: MinecraftServer,
    val loader: Loader,
    main: MainThread,
    history: History,
    bus: EventBus,
    override val statistics: ModStatistics,
    experience: Experience,
    tokens: TokenStore,
    configRef: AtomicReference<WorldApiConfig>,
    vanished: Vanished,
    extensions: Extensions,
    tamed: Tamed,
    accounts: AdminAccounts,
    icons: Icons?,
    files: ServerFiles,
    /** Ticks per second, measured here: the game keeps no averages of its own. */
    val ticks: TickRate,
) : WorldApi(main, history, bus, tokens, configRef, loader.version, vanished, extensions, tamed, experience, accounts, icons, files) {
    val commands = ModCommands(server, loader)

    /** A restart with a countdown; a singleplayer world has nothing to bring it back, so it refuses one. */
    val restart by lazy {
        Restart(main, CoroutineScope(SupervisorJob() + Dispatchers.Default), { text ->
            server.playerList.broadcastSystemMessage(Component.literal(text).withStyle(ChatFormatting.GOLD), false)
        }, {
            server.playerList.saveAll()
            server.saveAllChunks(true, true, true)
            server.halt(false)
        })
    }

    /** Whether this is the server under a singleplayer world, opened to LAN or not. */
    val integrated: Boolean get() = !server.isDedicatedServer

    override val scripts: Scripts? = null

    override suspend fun playerName(uuid: UUID): String? = main.call {
        server.playerList.getPlayer(uuid)?.gameProfile?.name() ?: server.services().nameToIdCache().get(uuid).map { it.name() }.orElse(null)
    }

    override suspend fun online() = main.call { server.playerList.players.map { it.uuid to loader.vanished(it) } }

    override suspend fun calendar() = main.call {
        val overworld = server.getLevel(Level.OVERWORLD) ?: server.allLevels.first()
        overworld.defaultClockTime / 24_000 + 1 to firstPlayed()
    }

    /** When the first player first joined: the oldest playerdata file, since the game keeps no first-join time. */
    private fun firstPlayed(): Long? {
        val folder = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.PLAYER_DATA_DIR)
        if (!Files.isDirectory(folder)) return null
        return Files.list(folder).use { stream ->
            stream.filter { it.name.endsWith(".dat") }.map {
                runCatching { Files.readAttributes(it, java.nio.file.attribute.BasicFileAttributes::class.java).creationTime().toMillis() }.getOrDefault(0L)
            }.toList()
        }.filter { it > 0 }.minOrNull()
    }

    override val serverConfigs: List<String>
        get() = listOf("server.properties", "ops.json", "whitelist.json", "banned-players.json", "banned-ips.json") + loader.serverConfigs

    override val configNote = "Saved. The server reads server.properties at start, and a mod reads its config at start or when it notices the file change. " +
        "Restart for the change to take effect where the mod doesn't watch its file."

    override val yaml = LineYaml

    override suspend fun configFolders(): Map<String, List<Path>> {
        val config = loader.config
        return loader.mods().associate { mod ->
            val roots = runCatching {
                Files.list(config).use { stream -> stream.filter { it.name == mod.id || it.name.startsWith("${mod.id}.") || it.name.startsWith("${mod.id}-") }.toList() }
            }.getOrDefault(emptyList())
            mod.name to roots
        }
    }

    override suspend fun applyList(file: String, text: String) = main.call { ModLists.apply(server, file, text) }
}
