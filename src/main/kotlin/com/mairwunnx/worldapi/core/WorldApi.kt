package com.mairwunnx.worldapi.core

import com.mairwunnx.worldapi.config.WorldApiConfig
import com.mairwunnx.worldapi.events.EventBus
import com.mairwunnx.worldapi.script.ScriptManager
import com.mairwunnx.worldapi.stats.Experience
import com.mairwunnx.worldapi.stats.Statistics
import com.mairwunnx.worldapi.tokens.TokenStore
import com.mairwunnx.worldapi.store.History
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
) {
    val config: WorldApiConfig get() = configRef.get()
}
