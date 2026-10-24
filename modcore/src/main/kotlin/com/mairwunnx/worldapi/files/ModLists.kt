package com.mairwunnx.worldapi.files

import com.mairwunnx.worldapi.api.badRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.players.IpBanListEntry
import net.minecraft.server.players.NameAndId
import net.minecraft.server.players.UserBanListEntry
import net.minecraft.server.players.UserWhiteListEntry
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.UUID

/**
 * ops.json, whitelist.json, banned-players.json, and banned-ips.json on the mods, as Paper's `ServerLists` applies
 * them: WorldAPI reads the new content as the list it should become, adds and removes entries to match, and the game
 * saves the file itself.
 */
object ModLists {
    private const val SOURCE = "WorldAPI"
    private val dates = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z")

    /** Applies [text] as the new [file]; server thread only. Answers what changed, for the audit log. */
    fun apply(server: MinecraftServer, file: String, text: String): String {
        val entries = runCatching { Json.parseToJsonElement(text) as JsonArray }.getOrElse { badRequest("$file must be a JSON array: ${it.message}", "invalid_file") }
            .map { it as? JsonObject ?: badRequest("Every entry in $file must be an object", "invalid_file") }
        val players = server.playerList
        return when (file) {
            "ops.json" -> profiles(server, entries, file).let { wanted ->
                val current = players.ops.entries.mapNotNull { it.user }.associateBy { it.id() }
                (wanted.keys - current.keys).forEach { players.op(wanted.getValue(it)) }
                (current.keys - wanted.keys).forEach { players.deop(current.getValue(it)) }
                summary(wanted.keys - current.keys, current.keys - wanted.keys, "op", "deop")
            }
            "whitelist.json" -> profiles(server, entries, file).let { wanted ->
                val list = players.whiteList
                val current = list.entries.mapNotNull { it.user }.associateBy { it.id() }
                (wanted.keys - current.keys).forEach { list.add(UserWhiteListEntry(wanted.getValue(it))) }
                (current.keys - wanted.keys).forEach { list.remove(current.getValue(it)) }
                summary(wanted.keys - current.keys, current.keys - wanted.keys, "whitelist", "unwhitelist")
            }
            "banned-players.json" -> {
                val list = players.bans
                val wanted = entries.associateBy { uuid(it, file) }
                val current = list.entries.mapNotNull { entry -> entry.user?.let { it.id() to it } }.toMap()
                (wanted.keys - current.keys).forEach { id ->
                    val entry = wanted.getValue(id)
                    val profile = NameAndId(id, text(entry, "name") ?: server.services().nameToIdCache().get(id).map { it.name() }.orElse(""))
                    list.add(UserBanListEntry(profile, Date(), text(entry, "source") ?: SOURCE, expires(entry), text(entry, "reason")))
                    players.getPlayer(id)?.connection?.disconnect(KICKED)
                }
                (current.keys - wanted.keys).forEach { list.remove(current.getValue(it)) }
                summary(wanted.keys - current.keys, current.keys - wanted.keys, "ban", "pardon")
            }
            else -> {
                val list = players.ipBans
                val wanted = entries.associateBy { address(text(it, "ip") ?: badRequest("Every entry in $file needs an ip", "invalid_file")) }
                val current = list.entries.mapNotNull { it.user }.toSet()
                (wanted.keys - current).forEach { ip ->
                    val entry = wanted.getValue(ip)
                    list.add(IpBanListEntry(ip, Date(), text(entry, "source") ?: SOURCE, expires(entry), text(entry, "reason")))
                    players.getPlayersWithAddress(ip).forEach { it.connection.disconnect(KICKED) }
                }
                (current - wanted.keys).forEach { list.remove(it) }
                summary(wanted.keys - current, current - wanted.keys, "ban-ip", "pardon-ip")
            }
        }
    }

    /** What Paper's `Player.kick()` says. */
    private val KICKED: Component = Component.translatable("multiplayer.disconnect.kicked")

    private fun profiles(server: MinecraftServer, entries: List<JsonObject>, file: String): Map<UUID, NameAndId> = entries.associate { entry ->
        val id = uuid(entry, file)
        id to NameAndId(id, text(entry, "name") ?: server.services().nameToIdCache().get(id).map { it.name() }.orElse(""))
    }

    private fun uuid(entry: JsonObject, file: String): UUID =
        text(entry, "uuid")?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: badRequest("Every entry in $file needs a valid uuid", "invalid_file")

    private fun text(entry: JsonObject, key: String) = (entry[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    /** "forever" or absent means no end, as the game writes it. */
    private fun expires(entry: JsonObject): Date? = text(entry, "expires")?.takeUnless { it == "forever" }?.let {
        runCatching { Date.from(OffsetDateTime.parse(it, dates).toInstant()) }.getOrElse { _ -> badRequest("expires must look like 2026-10-19 12:00:00 +0000, or be forever", "invalid_file") }
    }

    /** The game keys IP bans by the address as text, as `/ban-ip` writes it. */
    private fun address(ip: String): String {
        if (!Regex("^[0-9.]+$|^[0-9a-fA-F:]+$").matches(ip)) badRequest("\"$ip\" isn't an IP address", "invalid_file")
        return runCatching { java.net.InetAddress.getByName(ip).hostAddress }.getOrElse { badRequest("\"$ip\" isn't an IP address", "invalid_file") }
    }

    private fun summary(added: Set<*>, removed: Set<*>, add: String, remove: String) =
        listOfNotNull(added.size.takeIf { it > 0 }?.let { "$add $it" }, removed.size.takeIf { it > 0 }?.let { "$remove $it" }).joinToString(", ").ifEmpty { "no change" }
}
