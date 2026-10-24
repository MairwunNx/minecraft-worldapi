package com.mairwunnx.worldapi.core

import com.mairwunnx.worldapi.admin.AdminAccounts
import com.mairwunnx.worldapi.admin.Icons
import com.mairwunnx.worldapi.api.ApiJson
import com.mairwunnx.worldapi.config.WorldApiConfig
import com.mairwunnx.worldapi.events.ApiEvent
import com.mairwunnx.worldapi.events.EventBus
import com.mairwunnx.worldapi.events.Topic
import com.mairwunnx.worldapi.events.Vanished
import com.mairwunnx.worldapi.extensions.Extensions
import com.mairwunnx.worldapi.files.ServerFiles
import com.mairwunnx.worldapi.model.LogLine
import com.mairwunnx.worldapi.stats.Tamed
import com.mairwunnx.worldapi.store.History
import com.mairwunnx.worldapi.tokens.TokenStore
import kotlinx.serialization.json.JsonObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * The services every handler and listener shares, on any platform; each platform's subclass adds its game. The config
 * swaps atomically on reload.
 */
abstract class WorldApi(
    val main: MainThread,
    val history: History,
    val bus: EventBus,
    val tokens: TokenStore,
    private val configRef: AtomicReference<WorldApiConfig>,
    val version: String,
    /** Vanished players, for code off the server thread. */
    val vanished: Vanished,
    /** Other plugins' or mods' namespaces, from the expansion API. */
    val extensions: Extensions,
    /** Animals each player has tamed, which WorldAPI counts itself. */
    val tamed: Tamed,
    /** Admin panel accounts and sessions. */
    val accounts: AdminAccounts,
    /** Item icons for the panel, from the game's own textures. */
    val icons: Icons?,
    /** The server folder, for /files. */
    val files: ServerFiles,
) {
    val config: WorldApiConfig get() = configRef.get()

    /** Recent console lines, filled by the platform's log appender. */
    val console = ConsoleLog({ config.limits.consoleLines }, { config.auth.tokens.map { it.token } }).also { log ->
        log.listener = { line ->
            if (bus.interest(Topic.SERVER_CONSOLE)) {
                bus.stream(ApiEvent(Topic.SERVER_CONSOLE, ApiJson.encodeToJsonElement(LogLine.serializer(), line) as JsonObject))
            }
        }
    }

    /** A player's name from the server's records, or null when it never saw them. */
    abstract suspend fun playerName(uuid: UUID): String?
}
