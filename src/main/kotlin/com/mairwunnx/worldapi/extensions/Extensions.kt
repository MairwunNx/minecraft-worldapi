package com.mairwunnx.worldapi.extensions

import com.mairwunnx.worldapi.events.ApiEvent
import com.mairwunnx.worldapi.events.EventBus
import com.mairwunnx.worldapi.events.Topic
import com.mairwunnx.worldapi.model.PlayerRef
import kotlinx.serialization.json.JsonObject
import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Function
import java.util.logging.Logger

/**
 * Other plugins' data. A plugin registers a namespace: resources WorldAPI reads from it when a client
 * asks, and event topics it publishes to. The plugin stays the source of truth: WorldAPI stores none
 * of its data, only the events it marks recorded, in the history like WorldAPI's own.
 */
class Extensions(private val bus: EventBus, private val logger: Logger) {
    /** One readable path of a namespace, like `players/{player}`, and the function that answers it. */
    class Resource(
        val path: String,
        val description: String,
        val public: Boolean,
        /** Run the function on the server thread; otherwise it runs on a worker and must be thread-safe. */
        val mainThread: Boolean,
        val get: Function<Map<String, String>, Any?>,
    ) {
        private val parts = path.trim('/').split('/')

        /** The path parameters when [requested] fits this path, like `{player}` → `Steve`; null when it doesn't. */
        fun match(requested: List<String>): Map<String, String>? {
            if (requested.size != parts.size) return null
            val params = HashMap<String, String>()
            parts.zip(requested).forEach { (part, value) ->
                if (part.startsWith("{") && part.endsWith("}")) params[part.substring(1, part.length - 1)] = value
                else if (part != value) return null
            }
            return params
        }
    }

    class Namespace(val name: String, val plugin: Plugin, val description: String, val resources: List<Resource>, val topics: List<Topic>)

    private val namespaces = ConcurrentHashMap<String, Namespace>()

    val all: Collection<Namespace> get() = namespaces.values.sortedBy { it.name }

    fun namespace(name: String): Namespace? = namespaces[name]

    fun register(namespace: Namespace) {
        val owner = namespaces[namespace.name]?.plugin
        require(owner == null || owner === namespace.plugin) { "The namespace ${namespace.name} belongs to ${owner?.name}" }
        namespaces[namespace.name] = namespace
        logger.info("${namespace.plugin.name} shares ${namespace.name}: ${namespace.resources.size} resources, ${namespace.topics.size} topics")
    }

    fun unregister(name: String) {
        namespaces.remove(name) ?: return
        Topic.forget(name)
    }

    /** Every namespace of a plugin that stopped. */
    fun unregisterAll(plugin: Plugin) = namespaces.values.filter { it.plugin === plugin }.forEach { unregister(it.name) }

    /** A plugin's event, on its way to the history and to WebSocket clients, from the server thread. */
    fun publish(namespace: String, event: String, data: JsonObject, player: PlayerRef?, world: String?) {
        val registered = namespaces[namespace] ?: throw IllegalArgumentException("The namespace $namespace isn't registered")
        val topic = registered.topics.firstOrNull { it.id == "ext.$namespace.$event" } ?: throw IllegalArgumentException("$namespace has no event $event")
        val apiEvent = ApiEvent(topic, data, player, world)
        if (Bukkit.isPrimaryThread()) bus.publish(apiEvent) else Bukkit.getScheduler().runTask(registered.plugin, Runnable { bus.publish(apiEvent) })
    }
}
