package com.mairwunnx.worldapi.core

import com.mairwunnx.worldapi.admin.AdminAccounts
import com.mairwunnx.worldapi.admin.Icons
import com.mairwunnx.worldapi.bukkit.isVanished
import com.mairwunnx.worldapi.config.WorldApiConfig
import com.mairwunnx.worldapi.events.EventBus
import com.mairwunnx.worldapi.events.Vanished
import com.mairwunnx.worldapi.extensions.Extensions
import com.mairwunnx.worldapi.files.ServerFiles
import com.mairwunnx.worldapi.script.ScriptManager
import com.mairwunnx.worldapi.stats.Experience
import com.mairwunnx.worldapi.stats.Statistics
import com.mairwunnx.worldapi.stats.Tamed
import com.mairwunnx.worldapi.store.History
import com.mairwunnx.worldapi.tokens.TokenStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.bukkit.Server
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** [WorldApi] on Paper: the shared services plus the Bukkit server and what reads it. */
class PaperApi(
    val server: Server,
    main: MainThread,
    history: History,
    bus: EventBus,
    override val statistics: Statistics,
    experience: Experience,
    override val scripts: ScriptManager,
    tokens: TokenStore,
    configRef: AtomicReference<WorldApiConfig>,
    version: String,
    vanished: Vanished = Vanished(),
    extensions: Extensions = Extensions(bus, main, java.util.logging.Logger.getLogger("WorldAPI")),
    tamed: Tamed,
    accounts: AdminAccounts,
    icons: Icons? = null,
    files: ServerFiles = ServerFiles(Path.of(""), Path.of("plugins", "WorldAPI"), Path.of("plugins", "WorldAPI", "backups")),
    /** The server's commands for GET /server/commands, rebuilt when plugins come and go. */
    val commands: com.mairwunnx.worldapi.commands.PaperCommands = com.mairwunnx.worldapi.commands.PaperCommands(server),
) : WorldApi(main, history, bus, tokens, configRef, version, vanished, extensions, tamed, experience, accounts, icons, files) {
    /** A restart with a countdown, scheduled through POST /server/restart. */
    val restart by lazy { Restart(server, main, CoroutineScope(SupervisorJob() + Dispatchers.Default)) }

    override suspend fun playerName(uuid: UUID): String? = main.call { server.getOfflinePlayer(uuid).name }

    override suspend fun online() = main.call { server.onlinePlayers.map { it.uniqueId to it.isVanished() } }

    override val serverConfigs = listOf(
        "server.properties", "bukkit.yml", "spigot.yml", "config/paper-global.yml", "config/paper-world-defaults.yml",
        "commands.yml", "help.yml", "permissions.yml", "ops.json", "whitelist.json", "banned-players.json", "banned-ips.json",
    )

    override val configNote = "Saved. The server reads server.properties, bukkit.yml, spigot.yml, and Paper's configs at start; " +
        "a plugin reads its config at start or on its own reload command. Restart, or reload that plugin, for the change to take effect."

    override val yaml = com.mairwunnx.worldapi.files.BukkitYaml

    override suspend fun configFolders() = main.call { server.pluginManager.plugins.associate { it.name to it.dataFolder.toPath() } }

    override suspend fun applyList(file: String, text: String) = main.call { com.mairwunnx.worldapi.files.ServerLists.apply(server, file, text) }

    override suspend fun calendar() = main.call {
        server.worlds.first().fullTime / 24_000 + 1 to server.offlinePlayers.map { it.firstPlayed }.filter { it > 0 }.minOrNull()
    }
}
