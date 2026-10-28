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
import com.mairwunnx.worldapi.files.ConfigValues
import com.mairwunnx.worldapi.files.ServerFiles
import com.mairwunnx.worldapi.model.LogLine
import com.mairwunnx.worldapi.script.Scripts
import com.mairwunnx.worldapi.stats.Experience
import com.mairwunnx.worldapi.stats.StatsSource
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
    /** Experience each player gained, which WorldAPI counts itself. */
    val experience: Experience,
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

    /** Everyone's statistics, for leaderboards and aggregates. */
    abstract val statistics: StatsSource

    /** Java scripts, or null on a platform that doesn't run them. */
    abstract val scripts: Scripts?

    /** A player's name from the server's records, or null when it never saw them. */
    abstract suspend fun playerName(uuid: UUID): String?

    /** Who is online now, each with whether a vanish plugin or mod hides them right now. */
    abstract suspend fun online(): List<Pair<UUID, Boolean>>

    /** The server's own configs, relative to its folder, in the order a person would look for them. */
    abstract val serverConfigs: List<String>

    /** What a saved config needs before it counts: a restart, or the owner's reload. */
    abstract val configNote: String

    /** Lists the server keeps in memory and saves over any edit, so writes go through it ([applyList]). */
    open val listFiles: Set<String> = setOf("ops.json", "whitelist.json", "banned-players.json", "banned-ips.json")

    /** How `/files/set` changes YAML on this platform. */
    abstract val yaml: ConfigValues.Yaml

    /** Where each plugin's or mod's configs live, by its name: folders to look through, or single files. */
    abstract suspend fun configFolders(): Map<String, List<java.nio.file.Path>>

    /** Applies [text] as the new content of one of [listFiles] through the server; answers what changed, for the audit log. */
    abstract suspend fun applyList(file: String, text: String): String

    /** What the API changed in worlds, to undo; each platform keeps its own copy of a block. */
    abstract val changes: com.mairwunnx.worldapi.changes.Changes<*>

    /** Every key in one of [com.mairwunnx.worldapi.routes.REGISTRIES], such as `minecraft:stone` in `block`. Any thread. */
    abstract fun registryKeys(registry: String): List<String>

    /** The overworld's day, counted from 1, and when the first player first joined, from the server's own records. */
    abstract suspend fun calendar(): Pair<Long, Long?>
}
