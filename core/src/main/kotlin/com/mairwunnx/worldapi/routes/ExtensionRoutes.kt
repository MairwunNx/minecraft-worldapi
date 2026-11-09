package com.mairwunnx.worldapi.routes

import com.mairwunnx.worldapi.api.Scope
import com.mairwunnx.worldapi.api.ApiException
import com.mairwunnx.worldapi.api.endpoints
import com.mairwunnx.worldapi.api.notFound
import com.mairwunnx.worldapi.events.Visibility
import com.mairwunnx.worldapi.api.ApiCall
import com.mairwunnx.worldapi.core.WorldApi
import com.mairwunnx.worldapi.extensions.ExtensionParamInfo
import com.mairwunnx.worldapi.extensions.Extensions
import com.mairwunnx.worldapi.extensions.JsonValues
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.CompletionStage

@Serializable
data class ExtensionResourceInfo(
    val path: String,
    val description: String,
    val public: Boolean,
    /** What it reads from the path and the query; empty for an expansion of API 1. */
    val params: List<ExtensionParamInfo> = emptyList(),
    /** The shape of the answer as JSON Schema, when the expansion names it. */
    val returns: JsonElement? = null,
)

@Serializable
data class ExtensionTopicInfo(
    val topic: String,
    val description: String,
    val public: Boolean,
    val recorded: Boolean,
    /** The shape of the event's data as JSON Schema; null for an expansion of API 1. */
    val data: JsonElement? = null,
)

@Serializable
data class ExtensionActionInfo(
    /** `POST` or `DELETE`. */
    val method: String,
    val path: String,
    val description: String,
    /** The permission a token needs to call it. */
    val permission: String,
    /** What it reads from the path, the query, and the body. */
    val params: List<ExtensionParamInfo> = emptyList(),
    /** The shape of the answer as JSON Schema, when the expansion names it. */
    val returns: JsonElement? = null,
)

@Serializable
data class ExtensionInfo(
    val namespace: String,
    val plugin: String,
    val version: String,
    val description: String,
    val resources: List<ExtensionResourceInfo>,
    val topics: List<ExtensionTopicInfo>,
    /** What a client may do through the plugin; never listed for anonymous clients. */
    val actions: List<ExtensionActionInfo> = emptyList(),
)

val extensionRoutes = endpoints("Extensions", Scope.SERVER_READ) {
    get<List<ExtensionInfo>>(
        "/extensions", "Other plugins’ data: their namespaces, resources, and event topics",
        description = "Plugins register these through the expansion API (see the reference). Anonymous clients see only what the plugin marks public.",
    ) {
        if (!config.extensions.enabled) return@get emptyList()
        val visibility = Visibility(principal, config)
        api.extensions.all.map { namespace ->
            ExtensionInfo(
                namespace.name, namespace.plugin.name, namespace.plugin.version, namespace.description,
                namespace.resources.filter { !principal.anonymous || (it.public && !visibility.hidden(namespace.name, "${namespace.name}/${it.path}")) }
                    .map { ExtensionResourceInfo(it.path, it.description, it.public, it.params, it.returns) },
                namespace.topics.filter(visibility::allows).map { topic -> topic.extension!!.let { ExtensionTopicInfo(topic.id, it.description, it.public, topic.recorded, it.data) } },
                if (principal.anonymous) emptyList() else namespace.actions.map { ExtensionActionInfo(it.method, it.path, it.description, it.permission.id, it.params, it.returns) },
            )
        }.filter { !principal.anonymous || it.resources.isNotEmpty() || it.topics.isNotEmpty() }
    }

    get<JsonElement>(
        "/extensions/{namespace}/{path...}", "One resource of a plugin, read from the plugin as it is now",
        description = "Query parameters pass through to the plugin; `GET /extensions` lists what each resource reads.",
    ) {
        if (!config.extensions.enabled) notFound("extension_not_found", "Extensions are off")
        val name = path("namespace")
        val namespace = api.extensions.namespace(name) ?: notFound("extension_not_found", "No plugin shares $name")
        val requested = path("path").split('/').filter { it.isNotEmpty() }
        val (resource, params) = namespace.resources.firstNotNullOfOrNull { resource -> resource.match(requested)?.let { resource to it } }
            ?: notFound("resource_not_found", "$name has no ${requested.joinToString("/")}")
        val hidden = Visibility(principal, config).hidden(name, "$name/${resource.path}")
        if (principal.anonymous && (!resource.public || hidden)) throw ApiException(HttpStatusCode.Unauthorized, "unauthorized", "This needs a token")
        val query = call.request.queryParameters.entries().associate { it.key to it.value.firstOrNull().orEmpty() }
        val apiCall = this
        answer(namespace, resource.mainThread) { resource.read(params, query, apiCall) }
    }

    post<JsonObject, JsonElement>(
        "/extensions/{namespace}/{path...}", "Does something through a plugin, as the plugin's action says", Scope.SERVER_READ,
        description = "Each action names the permission it needs and the body it reads in `GET /extensions`. Never anonymous; every call lands in the audit log.",
        orScopes = Scope.entries,
    ) { body -> act("POST", body) }

    delete<JsonElement>(
        "/extensions/{namespace}/{path...}", "Takes something away or ends it through a plugin, as the plugin's action says", Scope.SERVER_READ,
        description = "Each action names the permission it needs and the query it reads in `GET /extensions`. Never anonymous; every call lands in the audit log.",
        orScopes = Scope.entries,
    ) { act("DELETE", JsonObject(emptyMap())) }
}

/** Finds the plugin's action for this request, checks its permission, and runs it. */
private suspend fun ApiCall<WorldApi>.act(method: String, body: JsonObject): JsonElement {
    if (!config.extensions.enabled) notFound("extension_not_found", "Extensions are off")
    val name = path("namespace")
    val namespace = api.extensions.namespace(name) ?: notFound("extension_not_found", "No plugin shares $name")
    val requested = path("path").split('/').filter { it.isNotEmpty() }
    val (action, params) = namespace.actions.filter { it.method == method }.firstNotNullOfOrNull { action -> action.match(requested)?.let { action to it } }
        ?: notFound("action_not_found", "$name has no $method ${requested.joinToString("/")}")
    if (!principal.has(action.permission)) throw ApiException(HttpStatusCode.Forbidden, "missing_scope", "The token \"${principal.name}\" lacks the ${action.permission.id} scope")
    auditTarget = "$name/${requested.joinToString("/")}"
    val query = call.request.queryParameters.entries().associate { it.key to it.value.firstOrNull().orEmpty() }
    val apiCall = this
    return answer(namespace, action.mainThread) { action.run(params, query, body, apiCall) }
}

/** Runs a plugin's code where it asked to run, waits for a stage it answers with, and turns its exceptions into answers. */
private suspend fun ApiCall<WorldApi>.answer(namespace: Extensions.Namespace, mainThread: Boolean, run: () -> Any?): JsonElement {
    val value = try {
        val answer = if (mainThread) main { run() } else withContext(Dispatchers.IO) { run() }
        if (answer is CompletionStage<*>) withTimeout(TIMEOUT) { answer.await() } else answer
    } catch (error: IllegalArgumentException) {
        throw ApiException(HttpStatusCode.BadRequest, "invalid_request", error.message ?: "Bad request")
    } catch (error: NoSuchElementException) {
        notFound("not_found", error.message ?: "Not found")
    } catch (error: ApiException) {
        throw error
    } catch (error: Exception) {
        // An exception's message can name files, queries, or players; visitors get only that it failed.
        val why = if (principal.anonymous) "" else ": ${error.message}"
        throw ApiException(HttpStatusCode.BadGateway, "extension_failed", "${namespace.plugin.name} couldn't answer$why")
    }
    return JsonValues.of(value, places = seesCoordinates)
}

/** How long a plugin may take to answer. */
private const val TIMEOUT = 10_000L
