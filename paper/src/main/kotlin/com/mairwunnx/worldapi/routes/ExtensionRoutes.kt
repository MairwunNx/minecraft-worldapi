package com.mairwunnx.worldapi.routes

import com.mairwunnx.worldapi.api.Scope
import com.mairwunnx.worldapi.api.ApiException
import com.mairwunnx.worldapi.api.endpoints
import com.mairwunnx.worldapi.api.notFound
import com.mairwunnx.worldapi.events.Visibility
import com.mairwunnx.worldapi.extensions.JsonValues
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import java.util.concurrent.CompletionStage

@Serializable
data class ExtensionResourceInfo(val path: String, val description: String, val public: Boolean)

@Serializable
data class ExtensionTopicInfo(val topic: String, val description: String, val public: Boolean, val recorded: Boolean)

@Serializable
data class ExtensionInfo(
    val namespace: String,
    val plugin: String,
    val version: String,
    val description: String,
    val resources: List<ExtensionResourceInfo>,
    val topics: List<ExtensionTopicInfo>,
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
                namespace.name, namespace.plugin.name, namespace.plugin.pluginMeta.version, namespace.description,
                namespace.resources.filter { !principal.anonymous || (it.public && !visibility.hidden(namespace.name, "${namespace.name}/${it.path}")) }
                    .map { ExtensionResourceInfo(it.path, it.description, it.public) },
                namespace.topics.filter(visibility::allows).map { topic -> topic.extension!!.let { ExtensionTopicInfo(topic.id, it.description, it.public, topic.recorded) } },
            )
        }.filter { !principal.anonymous || it.resources.isNotEmpty() || it.topics.isNotEmpty() }
    }

    get<JsonElement>(
        "/extensions/{namespace}/{path...}", "One resource of a plugin, read from the plugin as it is now",
        description = "Query parameters pass through to the plugin; each resource documents its own.",
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
        val value = try {
            val apiCall = this
            val answer = if (resource.mainThread) main { resource.read(params, query, apiCall) } else withContext(Dispatchers.IO) { resource.read(params, query, apiCall) }
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
        JsonValues.of(value)
    }
}

/** How long a plugin may take to answer. */
private const val TIMEOUT = 10_000L
