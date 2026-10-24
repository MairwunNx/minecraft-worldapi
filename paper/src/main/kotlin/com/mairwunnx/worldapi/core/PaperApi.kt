package com.mairwunnx.worldapi.core

import com.mairwunnx.worldapi.admin.AdminAccounts
import com.mairwunnx.worldapi.admin.Icons
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
    val statistics: Statistics,
    val experience: Experience,
    val scripts: ScriptManager,
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
) : WorldApi(main, history, bus, tokens, configRef, version, vanished, extensions, tamed, accounts, icons, files) {
    /** A restart with a countdown, scheduled through POST /server/restart. */
    val restart by lazy { Restart(server, main, CoroutineScope(SupervisorJob() + Dispatchers.Default)) }

    override suspend fun playerName(uuid: UUID): String? = main.call { server.getOfflinePlayer(uuid).name }
}
