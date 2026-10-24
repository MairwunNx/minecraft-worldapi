package com.mairwunnx.worldapi.api

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** One permission. Tokens and accounts hold them directly or through groups (config.yml `auth.groups`). */
@Serializable(with = ScopeSerializer::class)
enum class Scope(val id: String, val description: String) {
    SERVER_READ("server:read", "Server, worlds, blocks, entities, plugins, and performance"),
    PLAYERS_READ("players:read", "Players, statistics, advancements, sessions, and leaderboards"),
    PLAYERS_PRIVATE("players:private", "Coordinates, inventories, ender chests, containers, and death places"),
    EVENTS_READ("events:read", "Event history and the WebSocket"),
    CHAT_READ("chat:read", "Chat in the history and on the WebSocket"),
    LOGS_READ("logs:read", "Console lines, the live console, players' commands, and files under logs/"),
    SYSTEM_READ("system:read", "The host: memory, disk, CPU, and container limits"),
    WORLD_WRITE("world:write", "Blocks, containers, entities, time, weather, rules, and the border"),
    PLAYERS_WRITE("players:write", "Player state, inventories, teleports, effects, and messages"),
    PLAYERS_MODERATE("players:moderate", "Kicks, bans, the whitelist, operators, vanished players, and IP addresses"),
    SERVER_CONSOLE("server:console", "Console commands"),
    COMMANDS_READ("commands:read", "The server's commands: names, aliases, plugins, descriptions, and usage"),
    SERVER_CONTROL("server:control", "Saving, restarting, and stopping the server, and world seeds"),
    FILES_READ("files:read", "Server and plugin configs"),
    FILES_WRITE("files:write", "Changing server and plugin configs; only the owner group has it unless you add it"),
    TOKENS_MANAGE("tokens:manage", "Tokens, accounts, and the audit log"),

    /** Load and run Java code on the server. Only the owner group has it: it reaches past every other limit. */
    CODE("code", "Java scripts: arbitrary code on the server"),
    ;

    companion object {
        private val byId = entries.associateBy { it.id }

        fun of(id: String): Scope? = byId[id]

        /** What 2.x scopes meant, so old tokens keep the access they had. */
        val legacy: Map<String, Set<Scope>> = mapOf(
            "read" to setOf(SERVER_READ, PLAYERS_READ, PLAYERS_PRIVATE, EVENTS_READ, CHAT_READ),
            "write:world" to setOf(WORLD_WRITE),
            "write:players" to setOf(PLAYERS_WRITE),
        )

        /** What anonymous clients may ever have; privacy settings and the path list narrow it further. */
        val anonymous = setOf(SERVER_READ, PLAYERS_READ, EVENTS_READ)

        /** The groups a fresh config.yml has. */
        val defaultGroups: Map<String, List<String>> = linkedMapOf(
            "viewer" to listOf("server:read", "players:read", "players:private", "events:read", "chat:read"),
            "moderator" to listOf("viewer", "logs:read", "commands:read", "players:write", "players:moderate"),
            "admin" to (entries - setOf(FILES_WRITE, CODE)).map { it.id },
            "owner" to entries.map { it.id },
        )
    }
}

/** Scopes go over the wire by id, such as `players:read`. */
object ScopeSerializer : KSerializer<Scope> {
    override val descriptor = PrimitiveSerialDescriptor("Scope", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Scope) = encoder.encodeString(value.id)
    override fun deserialize(decoder: Decoder): Scope = decoder.decodeString().let { Scope.of(it) ?: throw IllegalArgumentException("Unknown scope $it") }
}

/**
 * Turns a list of permissions and group names into permissions. Groups may name other groups.
 * The 2.x names `read`, `write:world`, and `write:players` keep their old meaning; `admin` is a
 * group and `code` a permission, as before.
 */
class Groups(private val groups: Map<String, List<String>>) {
    fun resolve(names: Collection<String>, unknown: (String) -> Unit = {}): Set<Scope> {
        val result = mutableSetOf<Scope>()
        fun visit(name: String, path: List<String>) {
            Scope.of(name)?.let { result += it; return }
            groups[name]?.let { members ->
                require(name !in path) { "Group $name includes itself: ${(path + name).joinToString(" → ")}" }
                members.forEach { visit(it, path + name) }
                return
            }
            Scope.legacy[name]?.let { result += it; return }
            unknown(name)
        }
        names.forEach { visit(it, emptyList()) }
        return result
    }

    fun contains(name: String) = name in groups || Scope.of(name) != null || name in Scope.legacy

    val names: Set<String> get() = groups.keys

    /** Throws on a cycle or an unknown member, so a broken config.yml fails to load instead of granting less. */
    fun validate() = groups.keys.forEach { group ->
        resolve(listOf(group)) { throw IllegalArgumentException("Group $group names $it, which is neither a permission nor a group") }
    }
}

/** Who made a request. `key` identifies the client for rate limits; it is never the token itself. */
data class Principal(val name: String, val scopes: Set<Scope>, val key: String) {
    /** By the key, not the name: a token may be called “anonymous” too. */
    val anonymous get() = key.startsWith(ANONYMOUS_KEY)

    fun has(scope: Scope) = scope in scopes

    companion object {
        const val ANONYMOUS = "anonymous"
        private const val ANONYMOUS_KEY = "ip:"
        fun anonymous(address: String) = Principal(ANONYMOUS, Scope.anonymous, "$ANONYMOUS_KEY$address")
    }
}
