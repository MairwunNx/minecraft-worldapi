package com.mairwunnx.worldapi.api

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable

/** Thrown anywhere in a handler; the HTTP layer turns it into `{ "error": { code, message } }`. */
class ApiException(val status: HttpStatusCode, val code: String, override val message: String) : RuntimeException(message)

@Serializable
data class ErrorBody(val error: ErrorDetail)

@Serializable
data class ErrorDetail(val code: String, val message: String)

fun badRequest(message: String, code: String = "invalid_request"): Nothing =
    throw ApiException(HttpStatusCode.BadRequest, code, message)

fun notFound(code: String, message: String): Nothing = throw ApiException(HttpStatusCode.NotFound, code, message)

fun conflict(code: String, message: String): Nothing = throw ApiException(HttpStatusCode.Conflict, code, message)

fun <T : Any> T?.orNotFound(code: String, message: () -> String): T = this ?: notFound(code, message())
