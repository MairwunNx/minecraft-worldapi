package com.mairwunnx.worldapi.extensions

import com.mairwunnx.worldapi.api.ApiCall
import com.mairwunnx.worldapi.api.Scope
import com.mairwunnx.worldapi.events.Topic
import com.mairwunnx.worldapi.expansion.Event
import com.mairwunnx.worldapi.expansion.ExpansionApi
import com.mairwunnx.worldapi.expansion.Request
import com.mairwunnx.worldapi.expansion.WorldApiExpansion
import com.mairwunnx.worldapi.expansion.WorldApiExpansions
import com.mairwunnx.worldapi.model.PlayerRef
import kotlinx.serialization.json.JsonObject
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.server.PluginDisableEvent
import org.bukkit.event.server.PluginEnableEvent
import org.bukkit.plugin.Plugin
import java.util.ServiceLoader
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

/**
 * The expansion API's host: expansions from `com.mairwunnx.worldapi.expansion` become namespaces in [Extensions], which
 * `/extensions`, the topics, and the history read. A broken expansion is refused with a line in the log and never stops
 * the plugin that brought it.
 */
class Expansions(
    private val extensions: Extensions,
    private val logger: Logger,
    /** The plugin an expansion belongs to, which owns its namespace and stops it. */
    private val ownerOf: (WorldApiExpansion) -> Plugin,
) : WorldApiExpansions.Host {
    /** The namespaces this host registered, by id, and their plugins. */
    private val owners = ConcurrentHashMap<String, Plugin>()

    override fun apiVersion(): String = ExpansionApi.VERSION

    override fun register(expansion: WorldApiExpansion) {
        val label = runCatching { expansion.id() }.getOrNull() ?: expansion.javaClass.name
        try {
            val required = expansion.requiresApi()
            require(ExpansionApi.satisfies(ExpansionApi.VERSION, required)) { "it needs expansion API $required, and this WorldAPI has ${ExpansionApi.VERSION}" }
            val id = expansion.id()?.takeIf(NAMESPACE::matches) ?: throw IllegalArgumentException("an id is 1 to 32 of a-z, 0-9, _ and -")
            val owner = ownerOf(expansion)
            extensions.namespace(id)?.plugin?.let { require(it === owner) { "the namespace $id belongs to ${it.name}" } }
            val resources = expansion.resources().orEmpty().map { resource ->
                val handler = resource.handler()
                Extensions.Resource(resource.path(), resource.description(), resource.isPublic, resource.serverThread()) { path, query, call ->
                    handler.read(ExpansionRequest(path, query, call))
                }
            }
            val events = expansion.events().orEmpty()
            events.groupBy { it.name() }.filterValues { it.size > 1 }.keys.firstOrNull()?.let { throw IllegalArgumentException("it lists the event $it twice") }
            // Registered again, an expansion's topics are the new ones.
            Topic.forget(id)
            val topics = events.map { Topic.extension(id, it.name(), it.isRecorded, it.isPublic, it.description()) }
            extensions.register(Extensions.Namespace(id, owner, expansion.description().orEmpty(), resources, topics))
            owners[id] = owner
        } catch (error: Exception) {
            logger.warning("Refused the expansion $label: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    override fun unregister(id: String) {
        if (owners.remove(id) != null) extensions.unregister(id)
    }

    override fun publish(id: String, event: Event) {
        // An expansion this host refused publishes into nothing, as without WorldAPI.
        if (!owners.containsKey(id)) return
        val data = JsonValues.of(event.data()) as JsonObject
        val player = event.player()?.let { PlayerRef(it.toString(), event.playerName()) }
        extensions.publish(id, event.name(), data, player, event.world())
    }

    /** Every expansion of a plugin that stopped, out of WorldAPI and out of the API's list, so a reload doesn't bring it back. */
    fun stopped(plugin: Plugin) = owners.filterValues { it === plugin }.keys.forEach(WorldApiExpansions::unregister)

    private companion object {
        val NAMESPACE = Regex("[a-z0-9_-]{1,32}")
    }
}

/** One read of an expansion's resource, as the API shows it. */
class ExpansionRequest(private val path: Map<String, String>, private val query: Map<String, String>, private val call: ApiCall) : Request {
    override fun path(name: String): String = path[name] ?: throw IllegalArgumentException("No path parameter $name")

    override fun query(name: String): String? = query[name]?.takeIf { it.isNotEmpty() }

    override fun parameters(): Map<String, String> = query + path

    override fun anonymous(): Boolean = call.principal.anonymous

    override fun can(permission: String): Boolean = Scope.entries.firstOrNull { it.id == permission }?.let(call.principal::has) ?: false

    override fun seesCoordinates(): Boolean = call.seesCoordinates
}

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
        expansions.stopped(event.plugin)
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
