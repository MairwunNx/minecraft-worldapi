package com.mairwunnx.worldapi.model

import kotlinx.serialization.Serializable

/** One command of the server, by the name it's typed under. */
@Serializable
data class CommandInfo(
    /** Without the slash or a namespace, unless another plugin took the plain name. */
    val name: String,
    /** Other names it runs under, without namespaces. */
    val aliases: List<String> = emptyList(),
    /** Whose it is: `minecraft`, `bukkit`, `paper`, or a plugin's or mod's name; absent where the server can't tell. */
    val source: String? = null,
    /** What the command says about itself; written by its plugin. */
    val description: String? = null,
    /** How it's used, such as `/home [name]`; for Brigadier commands, every branch the console may run. */
    val usage: String? = null,
)

/** How many commands one source has. */
@Serializable
data class CommandSource(val source: String?, val commands: Int)

/** The commands one query or plugin found, best first. */
@Serializable
data class CommandHits(
    val query: String? = null,
    val plugin: String? = null,
    val commands: List<CommandInfo>,
    /** How many more matched than `limit` let through. */
    val more: Int? = null,
    val note: String? = null,
)

@Serializable
data class Commands(
    /** Every command the server has. */
    val total: Int,
    /** Each source with its count, when the request named neither a query nor a plugin. */
    val sources: List<CommandSource>? = null,
    /** One entry per query, in the order asked; otherwise one with the plugin's or the first commands. */
    val results: List<CommandHits>,
    val note: String? = null,
)
