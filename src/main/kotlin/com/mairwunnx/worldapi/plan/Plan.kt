package com.mairwunnx.worldapi.plan

import com.djrapitops.plan.capability.CapabilityService
import com.djrapitops.plan.extension.CallEvents
import com.djrapitops.plan.extension.DataExtension
import com.djrapitops.plan.extension.ExtensionService
import com.djrapitops.plan.extension.FormatType
import com.djrapitops.plan.extension.annotation.BooleanProvider
import com.djrapitops.plan.extension.annotation.Conditional
import com.djrapitops.plan.extension.annotation.NumberProvider
import com.djrapitops.plan.extension.annotation.PluginInfo
import com.djrapitops.plan.extension.annotation.StringProvider
import com.djrapitops.plan.extension.annotation.TableProvider
import com.djrapitops.plan.extension.icon.Color
import com.djrapitops.plan.extension.icon.Family
import com.djrapitops.plan.extension.icon.Icon
import com.djrapitops.plan.extension.table.Table
import com.mairwunnx.worldapi.core.WorldApi
import com.mairwunnx.worldapi.tokens.IssuedToken
import kotlinx.coroutines.runBlocking
import org.bukkit.plugin.Plugin
import java.time.Instant

/**
 * WorldAPI's section in Plan, when Plan is there: the server's API on the server page and each player's
 * tokens on theirs. Plan is optional, so its classes load only when [start] finds it enabled.
 */
object Plan {
    /** Registers the section, again after every Plan reload. Returns a handle that unregisters it, or null without Plan. */
    fun start(plugin: Plugin, api: WorldApi, webSocketClients: () -> Int, endpoints: Int): AutoCloseable? {
        if (!plugin.server.pluginManager.isPluginEnabled("Plan")) return null
        return Hook.start(plugin, Extension(api, webSocketClients, endpoints))
    }

    /** Touches Plan's classes; reached only when Plan is enabled. */
    private object Hook {
        fun start(plugin: Plugin, extension: Extension): AutoCloseable? {
            val capabilities = CapabilityService.getInstance()
            if (!capabilities.hasCapability("DATA_EXTENSION_VALUES") || !capabilities.hasCapability("DATA_EXTENSION_TABLES")) {
                plugin.logger.warning("This Plan is too old for WorldAPI's section; update Plan to show it")
                return null
            }
            fun register() = runCatching { ExtensionService.getInstance().register(extension) }
                .onFailure { plugin.logger.warning("Couldn't add WorldAPI's section to Plan: $it") }
            register()
            capabilities.registerEnableListener { enabled -> if (enabled) register() }
            plugin.logger.info("Plan found: WorldAPI's section shows on its pages")
            return AutoCloseable { runCatching { ExtensionService.getInstance().unregister(extension) } }
        }
    }

    /**
     * Plan calls these from its own threads, so they read only thread-safe state: the token list is
     * replaced whole, the hub counts in a concurrent set, and the event count is a database read.
     * Token values and digests never leave WorldAPI; Plan gets names, scopes, and expiry.
     */
    @PluginInfo(name = "WorldAPI", iconName = "plug", iconFamily = Family.SOLID, color = Color.TEAL)
    class Extension(
        private val api: WorldApi,
        // Named apart from the methods below: Plan calls the methods, and a clash would make them call themselves.
        private val countClients: () -> Int,
        private val endpointCount: Int,
    ) : DataExtension {
        override fun callExtensionMethodsOn() = arrayOf(
            CallEvents.SERVER_EXTENSION_REGISTER,
            CallEvents.SERVER_PERIODICAL,
            CallEvents.PLAYER_JOIN,
            CallEvents.PLAYER_LEAVE,
        )

        @StringProvider(text = "Version", priority = 100, iconName = "code-branch", iconColor = Color.TEAL)
        fun version(): String = api.version

        @NumberProvider(text = "Endpoints", priority = 90, description = "REST endpoints the API serves", iconName = "sitemap", iconColor = Color.TEAL)
        fun endpoints(): Long = endpointCount.toLong()

        @NumberProvider(text = "WebSocket clients", priority = 80, description = "Clients connected to the event stream now", iconName = "tower-broadcast", iconColor = Color.LIGHT_BLUE)
        fun webSocketClients(): Long = countClients().toLong()

        @NumberProvider(text = "Active tokens", priority = 70, description = "Tokens in config.yml and unexpired tokens made in game", iconName = "key", iconColor = Color.AMBER)
        fun activeTokens(): Long = (api.config.auth.tokens.size + api.tokens.tokens.count { !it.expired() }).toLong()

        @NumberProvider(text = "Events in history", priority = 60, description = "Events WorldAPI keeps for its history endpoints", iconName = "clock-rotate-left", iconColor = Color.GREY)
        fun eventsInHistory(): Long = runBlocking { api.history.eventCount() }

        @BooleanProvider(text = "Has API tokens", conditionName = "hasTokens", hidden = true)
        fun hasTokens(playerName: String): Boolean = tokensOf(playerName).isNotEmpty()

        @NumberProvider(text = "API tokens", priority = 100, description = "Unexpired tokens this player made with /worldapi token create", iconName = "key", iconColor = Color.AMBER, showInPlayerTable = true)
        fun tokenCount(playerName: String): Long = tokensOf(playerName).count { !it.expired() }.toLong()

        @Conditional("hasTokens")
        @TableProvider(tableColor = Color.AMBER)
        fun tokens(playerName: String): Table {
            val table = Table.builder()
                .columnOne("Token", Icon.called("key").of(Color.AMBER).build())
                .columnTwo("Scopes", Icon.called("lock-open").of(Color.TEAL).build())
                .columnThree("Expires", Icon.called("hourglass-half").of(Color.GREY).build())
            val now = Instant.now()
            tokensOf(playerName).forEach { token ->
                val expires = when {
                    token.expiresAt == null -> "Never"
                    token.expired(now) -> "Expired ${token.expiresAt}"
                    else -> token.expiresAt
                }
                table.addRow(token.name, token.scopes.joinToString { it.id }, expires)
            }
            return table.build()
        }

        private fun tokensOf(playerName: String): List<IssuedToken> = api.tokens.tokens.filter { it.createdBy.equals(playerName, ignoreCase = true) }
    }
}
