package com.mairwunnx.worldapi.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class Scope(val id: String) {
    @SerialName("read") READ("read"),
    @SerialName("write:world") WRITE_WORLD("write:world"),
    @SerialName("write:players") WRITE_PLAYERS("write:players"),
    @SerialName("admin") ADMIN("admin"),

    /** Load and run Java code on the server. Admin doesn't include it: it reaches past every other limit. */
    @SerialName("code") CODE("code"),
}

/** Who made a request. `key` identifies the client for rate limits; it is never the token itself. */
data class Principal(val name: String, val scopes: Set<Scope>, val key: String) {
    val anonymous get() = name == ANONYMOUS

    // admin includes every other scope except code, so one admin token covers normal control.
    fun has(scope: Scope) = scope in scopes || (Scope.ADMIN in scopes && scope != Scope.CODE)

    companion object {
        const val ANONYMOUS = "anonymous"
        fun anonymous(address: String) = Principal(ANONYMOUS, setOf(Scope.READ), "ip:$address")
    }
}
