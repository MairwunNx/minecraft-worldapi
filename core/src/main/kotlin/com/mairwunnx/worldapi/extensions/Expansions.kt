@file:Suppress("DEPRECATION")

package com.mairwunnx.worldapi.extensions

import com.mairwunnx.worldapi.api.ApiCall
import com.mairwunnx.worldapi.api.Scope
import com.mairwunnx.worldapi.events.Topic
import com.mairwunnx.worldapi.expansion.Event
import com.mairwunnx.worldapi.expansion.ExpansionApi
import com.mairwunnx.worldapi.expansion.Request
import com.mairwunnx.worldapi.expansion.WorldApiExpansion
import com.mairwunnx.worldapi.expansion.WorldApiExpansions
import com.mairwunnx.worldapi.expansion.v2.Expansion
import com.mairwunnx.worldapi.expansion.v2.Param
import com.mairwunnx.worldapi.expansion.v2.Permission
import com.mairwunnx.worldapi.expansion.v2.Place
import com.mairwunnx.worldapi.expansion.v2.Registry
import com.mairwunnx.worldapi.model.PlayerRef
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger
import com.mairwunnx.worldapi.expansion.v2.Event as EventV2
import com.mairwunnx.worldapi.expansion.v2.PlayerRef as PlayerRefV2
import com.mairwunnx.worldapi.expansion.v2.Request as RequestV2

/**
 * The expansion API's host: expansions of API 2 (`com.mairwunnx.worldapi.expansion.v2`) and of API 1, kept for plugins
 * built against it, become namespaces in [Extensions], which `/extensions`, the topics, and the history read. A broken
 * expansion is refused with a line in the log and never stops the plugin or mod that brought it.
 */
class Expansions(
    private val extensions: Extensions,
    private val logger: Logger,
    /** Someone who has played here, by name or UUID, for a parameter of the kind `player`. Safe off the server thread. */
    private val players: (String) -> PlayerRef? = { null },
    /** The plugin or mod an expansion's class belongs to, which owns its namespace and stops it. */
    private val ownerOf: (Class<*>) -> Extensions.Owner,
) : WorldApiExpansions.Host, Registry.Host {
    /** The namespaces this host registered, by id, and their owners. */
    private val owners = ConcurrentHashMap<String, Extensions.Owner>()

    /** Ids registered through API 2, so a stopped plugin leaves the right list. */
    private val second = ConcurrentHashMap.newKeySet<String>()

    override fun apiVersion(): String = ExpansionApi.VERSION

    override fun register(expansion: WorldApiExpansion) = refusing(runCatching { expansion.id() }.getOrNull() ?: expansion.javaClass.name) {
        val required = expansion.requiresApi()
        require(ExpansionApi.satisfies(ExpansionApi.VERSION, required) || ExpansionApi.satisfies(LAST_FIRST, required)) {
            "it needs expansion API $required, and this WorldAPI has ${ExpansionApi.VERSION}"
        }
        val id = expansion.id()?.takeIf(NAMESPACE::matches) ?: throw IllegalArgumentException("an id is 1 to 32 of a-z, 0-9, _ and -")
        val owner = owner(id, expansion.javaClass)
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
        second -= id
    }

    override fun register(expansion: Expansion) = refusing(expansion.id()) {
        val id = expansion.id()
        val owner = owner(id, expansion.javaClass)
        val resources = expansion.resources().map { resource ->
            val handler = resource.handler()
            val params = resource.params()
            val pathParams = params.filter { "{${it.name()}}" in resource.path() }
            Extensions.Resource(
                resource.path(), resource.description(), resource.isPublic, resource.serverThread(),
                params.map { param -> info(param, if (param in pathParams) "path" else "query") },
                resource.returns()?.let { if (resource.returnsList()) Schemas.listOf(it) else Schemas.of(it) },
            ) { path, query, call -> handler.read(SecondRequest(params, path, query, call, players).checked()) }
        }
        val actions = expansion.actions().map { action ->
            val permission = action.permission() ?: throw IllegalArgumentException("the action ${action.method()} ${action.path()} names no permission")
            val scope = Scope.entries.firstOrNull { it.id == permission.id() } ?: throw IllegalArgumentException("the action ${action.method()} ${action.path()} needs ${permission.id()}, which this WorldAPI doesn't know")
            val handler = action.handler()
            val params = action.params()
            Extensions.Action(
                action.method().name, action.path(), action.description(), scope, action.serverThread(),
                action.pathParams().map { info(it, "path") } + action.queryParams().map { info(it, "query") } + action.bodyParams().map { info(it, "body") },
                action.returns()?.let { if (action.returnsList()) Schemas.listOf(it) else Schemas.of(it) },
            ) { path, query, body, call -> handler.read(SecondRequest(params, path, query, call, players, body).checked()) }
        }
        Topic.forget(id)
        val topics = expansion.events().map { event ->
            Topic.extension(id, event.name(), event.isRecorded, event.isPublic, event.description(), Schemas.of(event.payload()), Schemas.placeKeys(event.payload()))
        }
        extensions.register(Extensions.Namespace(id, owner, expansion.description(), resources, topics, actions))
        owners[id] = owner
        second += id
    }

    override fun unregister(id: String) {
        second -= id
        if (owners.remove(id) != null) extensions.unregister(id)
    }

    override fun publish(id: String, event: Event) {
        // An expansion this host refused publishes into nothing, as without WorldAPI.
        if (!owners.containsKey(id)) return
        val data = JsonValues.of(event.data()) as JsonObject
        val player = event.player()?.let { PlayerRef(it.toString(), event.playerName()) }
        extensions.publish(id, event.name(), data, player, event.world())
    }

    override fun publish(event: EventV2<*>, data: Record) {
        val id = event.expansion().id()
        if (!owners.containsKey(id)) return
        val json = JsonValues.of(data) as JsonObject
        val top = data.javaClass.recordComponents.map { it.accessor.apply { isAccessible = true }.invoke(data) }
        val player = top.firstNotNullOfOrNull { it as? PlayerRefV2 }?.let { PlayerRef(it.uuid().toString(), it.name()) }
        val world = top.firstNotNullOfOrNull { it as? Place }?.world()
        val others = buildSet { collectPlayers(data, this, 0) } - setOfNotNull(player?.uuid)
        extensions.publish(id, event.name(), json, player, world, others)
    }

    /** Every expansion of a plugin that stopped, out of WorldAPI and out of the API's list, so a reload doesn't bring it back. */
    fun stopped(owner: Extensions.Owner) = owners.filterValues { it == owner }.keys.forEach { id ->
        if (id in second) Registry.unregister(id) else WorldApiExpansions.unregister(id)
    }

    private fun owner(id: String, type: Class<*>): Extensions.Owner {
        val owner = ownerOf(type)
        extensions.namespace(id)?.plugin?.let { require(it == owner) { "the namespace $id belongs to ${it.name}" } }
        return owner
    }

    /** Runs a registration and logs, instead of throwing, why it failed. */
    private fun refusing(label: String, register: () -> Unit) {
        try {
            register()
        } catch (error: LinkageError) {
            // Built against a newer API: a class or method this WorldAPI doesn't have.
            logger.warning("Refused the expansion $label: it needs a newer WorldAPI (${error.message ?: error.javaClass.simpleName})")
        } catch (error: Exception) {
            logger.warning("Refused the expansion $label: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun collectPlayers(value: Any?, into: MutableSet<String>, depth: Int) {
        if (depth > 8) return
        when (value) {
            is PlayerRefV2 -> into += value.uuid().toString()
            is Record -> value.javaClass.recordComponents.forEach { collectPlayers(it.accessor.apply { isAccessible = true }.invoke(value), into, depth + 1) }
            is Iterable<*> -> value.forEach { collectPlayers(it, into, depth + 1) }
            is Map<*, *> -> value.values.forEach { collectPlayers(it, into, depth + 1) }
            is Array<*> -> value.forEach { collectPlayers(it, into, depth + 1) }
        }
    }

    private fun info(param: Param<*>, where: String) = ExtensionParamInfo(
        param.name(), where, param.kind().name.lowercase(), param.description(),
        param.min().takeIf { param.kind() == Param.Kind.INTEGER }, param.max().takeIf { param.kind() == Param.Kind.INTEGER },
        param.fallback()?.let { if (it is Enum<*>) it.name.lowercase() else it.toString() },
        param.choices().takeIf { param.kind() == Param.Kind.CHOICE },
        param.isRequired() || where == "path",
    )

    private companion object {
        val NAMESPACE = Regex("[a-z0-9_-]{1,32}")

        /** The last version of API 1: its expansions still run. */
        const val LAST_FIRST = "1.0.0"
    }
}

/** One read of an API 1 expansion's resource, as the API shows it. */
class ExpansionRequest(private val path: Map<String, String>, private val query: Map<String, String>, private val call: ApiCall<*>) : Request {
    override fun path(name: String): String = path[name] ?: throw IllegalArgumentException("No path parameter $name")

    override fun query(name: String): String? = query[name]?.takeIf { it.isNotEmpty() }

    override fun parameters(): Map<String, String> = query + path

    override fun anonymous(): Boolean = call.principal.anonymous

    override fun can(permission: String): Boolean = Scope.entries.firstOrNull { it.id == permission }?.let(call.principal::has) ?: false

    override fun seesCoordinates(): Boolean = call.seesCoordinates
}

/** One read of an API 2 expansion's resource: its declared parameters, read as they say. */
class SecondRequest(
    private val params: List<Param<*>>,
    private val path: Map<String, String>,
    private val query: Map<String, String>,
    private val call: ApiCall<*>,
    private val players: (String) -> PlayerRef?,
    /** An action's JSON body; empty for a resource. */
    private val body: JsonObject = JsonObject(emptyMap()),
) : RequestV2 {
    /** Reads every parameter but players once, so a bad or missing value answers 400 even when the handler skips it. */
    fun checked() = apply {
        params.forEach { if (it.kind() != Param.Kind.PLAYER) get(it) else if (it.isRequired() && raw(it).isNullOrEmpty()) throw IllegalArgumentException("${it.name()} is required") }
    }

    override fun <T> get(param: Param<T>): T {
        check(param in params) { "The resource doesn't declare the parameter ${param.name()}" }
        if (param.kind() == Param.Kind.PLACE) return param.type().cast(place(param))
        val raw = raw(param)
        if (param.kind() != Param.Kind.PLAYER) return param.parse(raw)
        if (raw.isNullOrEmpty()) return param.fallback()
        val found = players(raw.trim()) ?: throw NoSuchElementException("No player \"$raw\" has played here")
        return param.type().cast(PlayerRefV2(UUID.fromString(found.uuid), found.name))
    }

    /** The text a client sent: from the path, the query, or a body's field that isn't an object or a list. */
    private fun raw(param: Param<*>): String? = path[param.name()] ?: query[param.name()] ?: when (val field = body[param.name()]) {
        null, is JsonNull -> null
        is JsonPrimitive -> field.content
        else -> throw IllegalArgumentException("${param.name()} must be a plain value")
    }

    private fun place(param: Param<*>): Place? {
        val field = body[param.name()]
        if (field == null || field is JsonNull) {
            if (param.isRequired()) throw IllegalArgumentException("${param.name()} is required")
            return null
        }
        val place = field as? JsonObject ?: throw IllegalArgumentException("${param.name()} must be an object of world, x, y, and z")
        val world = (place["world"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw IllegalArgumentException("${param.name()}.world must be a world's name")
        fun axis(name: String) = (place[name] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() } ?: throw IllegalArgumentException("${param.name()}.$name must be a number")
        return Place(world, axis("x"), axis("y"), axis("z"))
    }

    override fun anonymous(): Boolean = call.principal.anonymous

    override fun seesCoordinates(): Boolean = call.seesCoordinates

    override fun can(permission: Permission): Boolean = Scope.entries.firstOrNull { it.id == permission.id() }?.let(call.principal::has) ?: false
}
