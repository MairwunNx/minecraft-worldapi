package com.mairwunnx.worldapi.extensions

import com.mairwunnx.worldapi.expansion.WorldApiExpansion
import com.mairwunnx.worldapi.expansion.WorldApiExpansions
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.server.PluginDisableEvent
import org.bukkit.event.server.PluginEnableEvent
import org.bukkit.plugin.Plugin
import java.util.ServiceLoader
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

/** A plugin as the owner of its namespaces. */
val Plugin.owner get() = Extensions.Owner(name, this) { pluginMeta.version }

/**
 * Expansions plugins name in `META-INF/services/com.mairwunnx.worldapi.expansion.WorldApiExpansion`, found in each
 * plugin's own jar when it's enabled (and in every plugin already on when WorldAPI starts), and taken out again with
 * every expansion of a plugin when it's disabled. A plugin that registers in code needs only the last part.
 */
class ExpansionPlugins(private val self: Plugin, private val expansions: Expansions, private val logger: Logger) : Listener {
    private val scanned = ConcurrentHashMap.newKeySet<String>()

    fun scanAll() = self.server.pluginManager.plugins.filter { it.isEnabled && it !== self }.forEach(::scan)

    @EventHandler
    fun enabled(event: PluginEnableEvent) {
        if (event.plugin !== self) scan(event.plugin)
    }

    @EventHandler
    fun disabled(event: PluginDisableEvent) {
        scanned.remove(event.plugin.name)
        expansions.stopped(event.plugin.owner)
    }

    private fun scan(plugin: Plugin) {
        if (!scanned.add(plugin.name)) return
        val loader = plugin.javaClass.classLoader
        try {
            ServiceLoader.load(WorldApiExpansion::class.java, loader).stream()
                // The plugin's own classes only: the loaders above it are everyone's.
                .filter { it.type().classLoader === loader }
                .forEach { provider ->
                    runCatching { WorldApiExpansions.register(provider.get()) }
                        .onFailure { logger.warning("Couldn't start ${plugin.name}'s expansion ${provider.type().name}: ${it.message ?: it.javaClass.simpleName}") }
                }
        } catch (error: Throwable) {
            // A plugin that can't see WorldAPI's classes names a service it can't load.
            logger.warning("Couldn't read ${plugin.name}'s expansions: ${error.message ?: error.javaClass.simpleName}")
        }
    }
}
