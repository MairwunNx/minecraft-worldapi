package com.mairwunnx.worldapi.extensions

import com.mairwunnx.worldapi.api.ApiCall
import com.mairwunnx.worldapi.api.Scope
import com.mairwunnx.worldapi.core.MainThread
import com.mairwunnx.worldapi.events.ApiEvent
import com.mairwunnx.worldapi.events.EventBus
import com.mairwunnx.worldapi.events.Topic
import com.mairwunnx.worldapi.model.PlayerRef
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

/**
 * Other plugins' and mods' data. A plugin or mod registers a namespace through the expansion API ([Expansions]): resources WorldAPI
 * reads from it when a client asks, and event topics it publishes to. The plugin stays the source of truth: WorldAPI
 * stores none of its data, only the events it marks recorded, in the history like WorldAPI's own.
 */
class Extensions(private val bus: EventBus, private val main: MainThread, private val logger: Logger) {
    /** One readable path of a namespace, like `players/{player}`, and the function that answers it. */
    class Resource(
        val path: String,
        val description: String,
        val public: Boolean,
        /** Run the function on the server thread; otherwise it runs on a worker and must be thread-safe. */
        val mainThread: Boolean,
        /** The parameters an API 2 expansion declared; API 1 names none. */
        val params: List<ExtensionParamInfo> = emptyList(),
        /** The shape of the answer, when the expansion named its record. */
        val returns: JsonElement? = null,
        /** Answers with the path's parameters, the query's, and the call they came with. */
        val read: (path: Map<String, String>, query: Map<String, String>, call: ApiCall<*>) -> Any?,
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

    /** The plugin or mod a namespace belongs to: its name, and whatever the platform knows it by. */
    class Owner(val name: String, val key: Any, private val versionOf: () -> String) {
        val version get() = versionOf()

        override fun equals(other: Any?) = other is Owner && other.key === key
        override fun hashCode() = System.identityHashCode(key)
    }

    /** One thing clients may do, `POST` or `DELETE` on a path, and the permission it needs. Never anonymous. */
    class Action(
        val method: String,
        val path: String,
        val description: String,
        val permission: Scope,
        val mainThread: Boolean,
        val params: List<ExtensionParamInfo>,
        val returns: JsonElement?,
        /** Answers with the path's parameters, the query's, the body's fields, and the call they came with. */
        val run: (path: Map<String, String>, query: Map<String, String>, body: JsonObject, call: ApiCall<*>) -> Any?,
    ) {
        private val pattern = Resource(path, description, false, mainThread) { _, _, _ -> null }

        fun match(requested: List<String>): Map<String, String>? = pattern.match(requested)
    }

    class Namespace(
        val name: String,
        val plugin: Owner,
        val description: String,
        val resources: List<Resource>,
        val topics: List<Topic>,
        val actions: List<Action> = emptyList(),
    )

    private val namespaces = ConcurrentHashMap<String, Namespace>()

    val all: Collection<Namespace> get() = namespaces.values.sortedBy { it.name }

    fun namespace(name: String): Namespace? = namespaces[name]

    fun register(namespace: Namespace) {
        val owner = namespaces[namespace.name]?.plugin
        require(owner == null || owner == namespace.plugin) { "The namespace ${namespace.name} belongs to ${owner?.name}" }
        namespaces[namespace.name] = namespace
        val actions = if (namespace.actions.isEmpty()) "" else ", ${namespace.actions.size} actions"
        logger.info("${namespace.plugin.name} shares ${namespace.name}: ${namespace.resources.size} resources, ${namespace.topics.size} topics$actions")
    }

    fun unregister(name: String) {
        namespaces.remove(name) ?: return
        Topic.forget(name)
    }

    /** A plugin's event, on its way to the history and to WebSocket clients, from the server thread. */
    fun publish(namespace: String, event: String, data: JsonObject, player: PlayerRef?, world: String?, others: Set<String> = emptySet()) {
        val registered = namespaces[namespace] ?: throw IllegalArgumentException("The namespace $namespace isn't registered")
        val topic = registered.topics.firstOrNull { it.id == "ext.$namespace.$event" } ?: throw IllegalArgumentException("$namespace has no event $event")
        val apiEvent = ApiEvent(topic, data, player, world, others = others)
        main.execute { bus.publish(apiEvent) }
    }
}

/** One parameter of a resource, as `GET /extensions` lists it. */
@Serializable
data class ExtensionParamInfo(
    val name: String,
    /** `path`, `query`, or `body`. */
    @SerialName("in") val where: String,
    /** `integer`, `text`, `flag`, `choice`, `player` (a name or a UUID), or `place` (an object of `world`, `x`, `y`, and `z`). */
    val kind: String,
    val description: String,
    val min: Long? = null,
    val max: Long? = null,
    /** What a missing value reads as. */
    val fallback: String? = null,
    val choices: List<String>? = null,
    /** Whether a request without it answers 400; a path parameter always is. */
    val required: Boolean = false,
)
