package com.mairwunnx.worldapi.files

import com.destroystokyo.paper.profile.PlayerProfile
import com.mairwunnx.worldapi.api.badRequest
import io.papermc.paper.ban.BanListType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.bukkit.BanEntry
import org.bukkit.Server
import java.net.InetAddress
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.UUID

/**
 * ops.json, whitelist.json, banned-players.json, and banned-ips.json: the server keeps them in
 * memory and saves over any edit, so a write to one goes through the server instead. WorldAPI reads
 * the new content as the list it should become, adds and removes entries to match, and the server
 * saves the file itself.
 */
object ServerLists {
    val files = setOf("ops.json", "whitelist.json", "banned-players.json", "banned-ips.json")

    private const val SOURCE = "WorldAPI"
    private val dates = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z")

    /** Applies [text] as the new [file]; server thread only. Answers what changed, for the audit log. */
    fun apply(server: Server, file: String, text: String): String {
        val entries = runCatching { Json.parseToJsonElement(text) as JsonArray }.getOrElse { badRequest("$file must be a JSON array: ${it.message}", "invalid_file") }
            .map { it as? JsonObject ?: badRequest("Every entry in $file must be an object", "invalid_file") }
        return when (file) {
            "ops.json" -> players(entries, file).let { wanted ->
                val current = server.operators.map { it.uniqueId }.toSet()
                (wanted - current).forEach { server.getOfflinePlayer(it).isOp = true }
                (current - wanted).forEach { server.getOfflinePlayer(it).isOp = false }
                summary(wanted - current, current - wanted, "op", "deop")
            }
            "whitelist.json" -> players(entries, file).let { wanted ->
                val current = server.whitelistedPlayers.map { it.uniqueId }.toSet()
                (wanted - current).forEach { server.getOfflinePlayer(it).isWhitelisted = true }
                (current - wanted).forEach { server.getOfflinePlayer(it).isWhitelisted = false }
                summary(wanted - current, current - wanted, "whitelist", "unwhitelist")
            }
            "banned-players.json" -> {
                val list = server.getBanList(BanListType.PROFILE)
                val wanted = entries.associateBy { uuid(it, file) }
                val current = list.getEntries<BanEntry<PlayerProfile>>().mapNotNull { entry -> entry.banTarget.id?.let { it to entry } }.toMap()
                (wanted.keys - current.keys).forEach { id ->
                    val entry = wanted.getValue(id)
                    val profile = server.createProfile(id, text(entry, "name"))
                    list.addBan(profile, text(entry, "reason"), expires(entry), text(entry, "source") ?: SOURCE)
                    server.getPlayer(id)?.kick()
                }
                (current.keys - wanted.keys).forEach { list.pardon(current.getValue(it).banTarget) }
                summary(wanted.keys - current.keys, current.keys - wanted.keys, "ban", "pardon")
            }
            else -> {
                val list = server.getBanList(BanListType.IP)
                val wanted = entries.associateBy { address(text(it, "ip") ?: badRequest("Every entry in $file needs an ip", "invalid_file")) }
                val current = list.getEntries<BanEntry<InetAddress>>().associateBy { it.banTarget }
                (wanted.keys - current.keys).forEach { ip ->
                    val entry = wanted.getValue(ip)
                    list.addBan(ip, text(entry, "reason"), expires(entry), text(entry, "source") ?: SOURCE)
                    server.onlinePlayers.filter { it.address?.address == ip }.forEach { it.kick() }
                }
                (current.keys - wanted.keys).forEach { list.pardon(it) }
                summary(wanted.keys - current.keys, current.keys - wanted.keys, "ban-ip", "pardon-ip")
            }
        }
    }

    private fun players(entries: List<JsonObject>, file: String) = entries.map { uuid(it, file) }.toSet()

    private fun uuid(entry: JsonObject, file: String): UUID =
        text(entry, "uuid")?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: badRequest("Every entry in $file needs a valid uuid", "invalid_file")

    private fun text(entry: JsonObject, key: String) = (entry[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    /** "forever" or absent means no end, as the game writes it. */
    private fun expires(entry: JsonObject): Date? = text(entry, "expires")?.takeUnless { it == "forever" }?.let {
        runCatching { Date.from(OffsetDateTime.parse(it, dates).toInstant()) }.getOrElse { _ -> badRequest("expires must look like 2026-10-19 12:00:00 +0000, or be forever", "invalid_file") }
    }

    private fun address(ip: String): InetAddress {
        if (!Regex("^[0-9.]+$|^[0-9a-fA-F:]+$").matches(ip)) badRequest("\"$ip\" isn't an IP address", "invalid_file")
        return runCatching { InetAddress.getByName(ip) }.getOrElse { badRequest("\"$ip\" isn't an IP address", "invalid_file") }
    }

    private fun summary(added: Set<*>, removed: Set<*>, add: String, remove: String) =
        listOfNotNull(added.size.takeIf { it > 0 }?.let { "$add $it" }, removed.size.takeIf { it > 0 }?.let { "$remove $it" }).joinToString(", ").ifEmpty { "no change" }
}
