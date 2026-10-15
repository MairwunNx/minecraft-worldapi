package com.mairwunnx.worldapi.core

import com.mairwunnx.worldapi.bukkit.Vanished
import com.mairwunnx.worldapi.config.WorldApiConfig
import com.mairwunnx.worldapi.events.EventBus
import com.mairwunnx.worldapi.extensions.Extensions
import com.mairwunnx.worldapi.script.ScriptManager
import com.mairwunnx.worldapi.stats.Experience
import com.mairwunnx.worldapi.stats.Statistics
import com.mairwunnx.worldapi.tokens.TokenStore
import com.mairwunnx.worldapi.store.History
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.bukkit.Server
import java.util.concurrent.atomic.AtomicReference

/** The services every handler and listener shares. The config swaps atomically on reload. */
class WorldApi(
    val server: Server,
    val main: MainThread,
    val history: History,
    val bus: EventBus,
    val statistics: Statistics,
    val experience: Experience,
    val scripts: ScriptManager,
    val tokens: TokenStore,
    private val configRef: AtomicReference<WorldApiConfig>,
    val version: String,
    /** Vanished players, for code off the server thread; on it, `Player.isVanished` reads the plugins' mark directly. */
    val vanished: Vanished = Vanished(),
    /** Other plugins' namespaces, from the extension bridge. */
    val extensions: Extensions = Extensions(bus, java.util.logging.Logger.getLogger("WorldAPI")),
) {
    val config: WorldApiConfig get() = configRef.get()

    /** Recent console lines, filled by the plugin's Log4j appender. */
    val console = ConsoleLog({ config.limits.consoleLines }, { config.auth.tokens.map { it.token } })

    /** A restart with a countdown, scheduled through POST /server/restart. */
    val restart by lazy { Restart(server, main, CoroutineScope(SupervisorJob() + Dispatchers.Default)) }
}
