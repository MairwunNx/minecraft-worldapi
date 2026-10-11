package com.mairwunnx.worldapi.api

import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer

/**
 * One REST operation: the route, who may call it, its typed body and response, and the handler.
 * The HTTP server and the OpenAPI document both read this list, so the docs can't drift from
 * the code.
 */
class Endpoint(
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
    val handler: suspend ApiCall.(Any?) -> Any?,
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
    integer("limit", "How many items to return; the server caps it"),
    string("cursor", "The `nextCursor` from the previous page"),
)

/** A request without a body. An empty body or `{}` both decode to it. */
@Serializable
class NoBody

/** Collects the endpoints of one resource group, all under one OpenAPI tag. */
class EndpointGroup(val tag: String) {
    val endpoints = mutableListOf<Endpoint>()

    @PublishedApi
    internal fun add(
        method: HttpMethod, path: String, summary: String, description: String?, scope: Scope,
        query: List<QueryParam>, body: KSerializer<*>?, response: KSerializer<*>?, status: HttpStatusCode,
        handler: suspend ApiCall.(Any?) -> Any?,
    ) {
        endpoints += Endpoint(method, path, tag, summary, description, scope, query, body, response, status, handler)
    }

    inline fun <reified R> get(
        path: String, summary: String, description: String? = null, scope: Scope = Scope.READ,
        query: List<QueryParam> = emptyList(), noinline handler: suspend ApiCall.() -> R,
    ) = add(HttpMethod.Get, path, summary, description, scope, query, null, responseOf<R>(), HttpStatusCode.OK) { handler() }

    inline fun <reified B, reified R> post(
        path: String, summary: String, scope: Scope, description: String? = null, query: List<QueryParam> = emptyList(),
        status: HttpStatusCode = HttpStatusCode.OK, noinline handler: suspend ApiCall.(B) -> R,
    ) = add(HttpMethod.Post, path, summary, description, scope, query, serializer<B>(), responseOf<R>(), status) {
        @Suppress("UNCHECKED_CAST") handler(it as B)
    }

    inline fun <reified B, reified R> put(
        path: String, summary: String, scope: Scope, description: String? = null, noinline handler: suspend ApiCall.(B) -> R,
    ) = add(HttpMethod.Put, path, summary, description, scope, emptyList(), serializer<B>(), responseOf<R>(), HttpStatusCode.OK) {
        @Suppress("UNCHECKED_CAST") handler(it as B)
    }

    inline fun <reified B, reified R> patch(
        path: String, summary: String, scope: Scope, description: String? = null, noinline handler: suspend ApiCall.(B) -> R,
    ) = add(HttpMethod.Patch, path, summary, description, scope, emptyList(), serializer<B>(), responseOf<R>(), HttpStatusCode.OK) {
        @Suppress("UNCHECKED_CAST") handler(it as B)
    }

    inline fun <reified R> delete(
        path: String, summary: String, scope: Scope, description: String? = null, query: List<QueryParam> = emptyList(),
        noinline handler: suspend ApiCall.() -> R,
    ) = add(HttpMethod.Delete, path, summary, description, scope, query, null, responseOf<R>(), HttpStatusCode.OK) { handler() }

    @PublishedApi
    internal inline fun <reified R> responseOf(): KSerializer<*>? = if (R::class == Unit::class) null else serializer<R>()
}

fun endpoints(tag: String, block: EndpointGroup.() -> Unit): List<Endpoint> = EndpointGroup(tag).apply(block).endpoints
