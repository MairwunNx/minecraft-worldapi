package com.mairwunnx.worldapi.api

import com.mairwunnx.worldapi.core.WorldApi
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer

/**
 * One REST operation: the route, who may call it, its typed body and response, and the handler.
 * The HTTP server and the OpenAPI document both read this list, so the docs can't drift from
 * the code. [A] is the platform's [WorldApi] the handler needs: an endpoint for any platform takes [WorldApi] itself.
 */
class Endpoint<in A : WorldApi>(
    val method: HttpMethod,
    val path: String,
    val tag: String,
    val summary: String,
    val description: String?,
    val scope: Scope,
    val query: List<QueryParam>,
    val body: KSerializer<*>?,
    /** Null means the operation answers 204 with no body. */
    val response: KSerializer<*>?,
    val status: HttpStatusCode,
    val handler: suspend ApiCall<A>.(Any?) -> Any?,
    /** Permissions that do instead of [scope]; the handler then checks which one fits the request. */
    val orScopes: List<Scope> = emptyList(),
    /** A permission the full answer needs on top, such as players:private for coordinates and items; without it parts are hidden or refused. */
    val also: Scope? = null,
) {
    val writes get() = method != HttpMethod.Get
    val pathParams: List<String> = Regex("\\{(\\w+)(\\.\\.\\.)?}").findAll(path).map { it.groupValues[1] }.toList()

    /** The path as OpenAPI writes it: a tail parameter `{key...}` becomes `{key}`. */
    val documentedPath get() = path.replace("...}", "}")
}

enum class ParamType(val schema: String) { STRING("string"), INTEGER("integer"), NUMBER("number"), BOOLEAN("boolean") }

class QueryParam(
    val name: String,
    val type: ParamType,
    val description: String,
    val required: Boolean = false,
    val values: List<String>? = null,
)

fun string(name: String, description: String, required: Boolean = false, values: List<String>? = null) =
    QueryParam(name, ParamType.STRING, description, required, values)

fun integer(name: String, description: String, required: Boolean = false) = QueryParam(name, ParamType.INTEGER, description, required)
fun number(name: String, description: String, required: Boolean = false) = QueryParam(name, ParamType.NUMBER, description, required)
fun boolean(name: String, description: String) = QueryParam(name, ParamType.BOOLEAN, description)

/** The standard `limit` and `cursor` pair for paginated lists. */
val pageParams = listOf(
    integer("limit", "How many items to return; the server caps it. 0 returns only `total` where a list has one"),
    string("cursor", "The `nextCursor` from the previous page"),
)

/** A request without a body. An empty body or `{}` both decode to it. */
@Serializable
class NoBody

/** Collects the endpoints of one resource group, all under one OpenAPI tag. Its GETs need [read] unless they say otherwise. */
class EndpointGroup<A : WorldApi>(val tag: String, val read: Scope) {
    val endpoints = mutableListOf<Endpoint<A>>()

    @PublishedApi
    internal fun add(
        method: HttpMethod, path: String, summary: String, description: String?, scope: Scope,
        query: List<QueryParam>, body: KSerializer<*>?, response: KSerializer<*>?, status: HttpStatusCode,
        orScopes: List<Scope> = emptyList(), also: Scope? = null,
        handler: suspend ApiCall<A>.(Any?) -> Any?,
    ) {
        endpoints += Endpoint(method, path, tag, summary, description, scope, query, body, response, status, handler, orScopes, also)
    }

    inline fun <reified R> get(
        path: String, summary: String, description: String? = null, scope: Scope = read,
        query: List<QueryParam> = emptyList(), orScopes: List<Scope> = emptyList(), also: Scope? = null,
        noinline handler: suspend ApiCall<A>.() -> R,
    ) = add(HttpMethod.Get, path, summary, description, scope, query, null, responseOf<R>(), HttpStatusCode.OK, orScopes, also) { handler() }

    inline fun <reified B, reified R> post(
        path: String, summary: String, scope: Scope, description: String? = null, query: List<QueryParam> = emptyList(),
        status: HttpStatusCode = HttpStatusCode.OK, noinline handler: suspend ApiCall<A>.(B) -> R,
    ) = add(HttpMethod.Post, path, summary, description, scope, query, serializer<B>(), responseOf<R>(), status) {
        @Suppress("UNCHECKED_CAST") handler(it as B)
    }

    inline fun <reified B, reified R> put(
        path: String, summary: String, scope: Scope, description: String? = null, noinline handler: suspend ApiCall<A>.(B) -> R,
    ) = add(HttpMethod.Put, path, summary, description, scope, emptyList(), serializer<B>(), responseOf<R>(), HttpStatusCode.OK) {
        @Suppress("UNCHECKED_CAST") handler(it as B)
    }

    inline fun <reified B, reified R> patch(
        path: String, summary: String, scope: Scope, description: String? = null, noinline handler: suspend ApiCall<A>.(B) -> R,
    ) = add(HttpMethod.Patch, path, summary, description, scope, emptyList(), serializer<B>(), responseOf<R>(), HttpStatusCode.OK) {
        @Suppress("UNCHECKED_CAST") handler(it as B)
    }

    inline fun <reified R> delete(
        path: String, summary: String, scope: Scope, description: String? = null, query: List<QueryParam> = emptyList(),
        noinline handler: suspend ApiCall<A>.() -> R,
    ) = add(HttpMethod.Delete, path, summary, description, scope, query, null, responseOf<R>(), HttpStatusCode.OK) { handler() }

    @PublishedApi
    internal inline fun <reified R> responseOf(): KSerializer<*>? = if (R::class == Unit::class) null else serializer<R>()
}

/** Endpoints for every platform: their handlers see only what [WorldApi] holds. */
fun endpoints(tag: String, read: Scope, block: EndpointGroup<WorldApi>.() -> Unit): List<Endpoint<WorldApi>> = endpointsOf(tag, read, block)

/** Endpoints whose handlers need a platform's own [WorldApi]; each platform names its type in its own `endpoints`. */
fun <A : WorldApi> endpointsOf(tag: String, read: Scope, block: EndpointGroup<A>.() -> Unit): List<Endpoint<A>> = EndpointGroup<A>(tag, read).apply(block).endpoints
