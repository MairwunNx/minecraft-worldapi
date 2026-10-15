package com.mairwunnx.worldapi.extensions

import com.mairwunnx.worldapi.events.Topic
import com.mairwunnx.worldapi.model.PlayerRef
import kotlinx.serialization.json.JsonObject
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.function.Function

/**
 * How other plugins reach WorldAPI without a library: a `java.util.function.Function` in Bukkit's
 * ServicesManager, registered by WorldAPI, that takes requests as maps of plain Java values and answers
 * with maps. Every request carries `v`, the version of this contract, so WorldAPI can grow it without
 * breaking older plugins. docs/reference.md (“Extensions”) describes it for plugin authors.
 *
 * - `register`: `plugin`, `namespace`, `description`, `resources` (each `path`, `description`,
 *   `public`, `mainThread`, `get`: a Function from path and query parameters to a value), and
 *   `events` (each `name`, `description`, `public`, `recorded`).
 * - `publish`: `namespace`, `event`, `data` (a map), and optionally `player` (a UUID), `playerName`,
 *   and `world`.
 * - `unregister`: `namespace`.
 */
class Bridge(private val extensions: Extensions, private val version: String) : Function<Map<String, Any?>, Any?> {
    override fun apply(request: Map<String, Any?>): Any? {
        val v = (request["v"] as? Number)?.toInt() ?: throw IllegalArgumentException("A request needs v, the contract version")
        require(v in 1..CONTRACT) { "WorldAPI $version speaks contract $CONTRACT at most, not $v" }
        when (val op = request["op"]) {
            "register" -> register(request)
            "publish" -> publish(request)
            "unregister" -> extensions.unregister(name(request["namespace"]))
            else -> throw IllegalArgumentException("Unknown op $op")
        }
        return mapOf("ok" to true, "contract" to CONTRACT, "version" to version)
    }

    private fun register(request: Map<String, Any?>) {
        val plugin = request["plugin"] as? Plugin ?: throw IllegalArgumentException("register needs plugin, the registering plugin")
        val namespace = name(request["namespace"])
        val resources = list(request["resources"]).map { raw ->
            val item = raw as? Map<*, *> ?: throw IllegalArgumentException("A resource is a map")
            val path = item["path"] as? String ?: throw IllegalArgumentException("A resource needs a path")
            require(PATH.matches(path)) { "Bad resource path $path" }
            @Suppress("UNCHECKED_CAST")
            val get = item["get"] as? Function<Map<String, String>, Any?> ?: throw IllegalArgumentException("The resource $path needs get, a Function")
            Extensions.Resource(path.trim('/'), item["description"] as? String ?: "", item["public"] == true, item["mainThread"] == true, get)
        }
        // A namespace registered again replaces its topics with the new ones.
        Topic.forget(namespace)
        val topics = list(request["events"]).map { raw ->
            val item = raw as? Map<*, *> ?: throw IllegalArgumentException("An event is a map")
            val event = item["name"] as? String ?: throw IllegalArgumentException("An event needs a name")
            require(EVENT.matches(event)) { "Bad event name $event" }
            Topic.extension(namespace, event, item["recorded"] == true, item["public"] == true, item["description"] as? String ?: "")
        }
        extensions.register(Extensions.Namespace(namespace, plugin, request["description"] as? String ?: "", resources, topics))
    }

    private fun publish(request: Map<String, Any?>) {
        val data = JsonValues.of(request["data"] ?: emptyMap<String, Any?>()) as? JsonObject ?: throw IllegalArgumentException("data must be a map")
        val player = request["player"]?.let { raw ->
            val uuid = raw as? UUID ?: runCatching { UUID.fromString(raw.toString()) }.getOrNull() ?: throw IllegalArgumentException("player must be a UUID")
            PlayerRef(uuid.toString(), request["playerName"] as? String)
        }
        val event = request["event"] as? String ?: throw IllegalArgumentException("publish needs event")
        extensions.publish(name(request["namespace"]), event, data, player, request["world"] as? String)
    }

    private fun name(raw: Any?): String = (raw as? String)?.takeIf(NAMESPACE::matches) ?: throw IllegalArgumentException("A namespace is 1 to 32 of a-z, 0-9, _ and -")

    private fun list(raw: Any?): List<Any?> = when (raw) {
        null -> emptyList()
        is Iterable<*> -> raw.toList()
        is Array<*> -> raw.toList()
        else -> throw IllegalArgumentException("Expected a list")
    }

    companion object {
        /** The contract version this WorldAPI speaks. */
        const val CONTRACT = 1
        private val NAMESPACE = Regex("[a-z0-9_-]{1,32}")
        private val EVENT = Regex("[a-z0-9_]+(\\.[a-z0-9_]+)*")
        private val PATH = Regex("/?([a-z0-9_-]+|\\{[a-z0-9_]+})(/([a-z0-9_-]+|\\{[a-z0-9_]+}))*/?")
    }
}
