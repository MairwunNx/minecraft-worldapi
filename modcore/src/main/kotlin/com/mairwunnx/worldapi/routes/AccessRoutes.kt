package com.mairwunnx.worldapi.routes

import com.mairwunnx.worldapi.api.ModCall
import com.mairwunnx.worldapi.api.Scope
import com.mairwunnx.worldapi.api.badRequest
import com.mairwunnx.worldapi.api.notFound
import com.mairwunnx.worldapi.game.findKnown
import com.mairwunnx.worldapi.game.uuidOrNull
import com.mairwunnx.worldapi.model.BanView
import com.mairwunnx.worldapi.model.Bans
import com.mairwunnx.worldapi.model.BansPatch
import com.mairwunnx.worldapi.model.Operators
import com.mairwunnx.worldapi.model.OperatorsPatch
import com.mairwunnx.worldapi.model.PlayerRef
import com.mairwunnx.worldapi.model.Whitelist
import com.mairwunnx.worldapi.model.WhitelistPatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.players.IpBanListEntry
import net.minecraft.server.players.NameAndId
import net.minecraft.server.players.UserBanListEntry
import net.minecraft.server.players.UserWhiteListEntry
import java.util.Date

private const val SOURCE = "WorldAPI"

val accessRoutes = endpoints("Access", Scope.PLAYERS_MODERATE) {
    get("/server/whitelist", "Whitelist state and players", scope = Scope.PLAYERS_MODERATE) {
        main { whitelist(api.server) }
    }

    patch<WhitelistPatch, Whitelist>("/server/whitelist", "Turn the whitelist on or off, add or remove players", Scope.PLAYERS_MODERATE) { patch ->
        val add = patch.add.map { profile(it) }
        val remove = patch.remove.map { profile(it) }
        auditTarget = (patch.add.map { "+$it" } + patch.remove.map { "-$it" }).joinToString(" ").ifEmpty { null }
        main {
            val players = api.server.playerList
            patch.enabled?.let(api.server::setUsingWhitelist)
            patch.enforced?.let(api.server::setEnforceWhitelist)
            add.forEach { players.whiteList.add(UserWhiteListEntry(it)) }
            remove.forEach { players.whiteList.remove(it) }
            whitelist(api.server)
        }
    }

    get("/server/ops", "Server operators", scope = Scope.PLAYERS_MODERATE) {
        main { operators(api.server) }
    }

    // An operator runs every command and /worldapi, which makes tokens with any permission: as much as the console.
    patch<OperatorsPatch, Operators>("/server/ops", "Make players operators or take it away", Scope.SERVER_CONSOLE, description = OPERATORS_NEED_CONSOLE) { patch ->
        val add = patch.add.map { profile(it) }
        val remove = patch.remove.map { profile(it) }
        auditTarget = (patch.add.map { "+$it" } + patch.remove.map { "-$it" }).joinToString(" ").ifEmpty { null }
        main {
            add.forEach { api.server.playerList.op(it) }
            remove.forEach { api.server.playerList.deop(it) }
            operators(api.server)
        }
    }

    get("/server/bans", "Banned players and IP addresses", scope = Scope.PLAYERS_MODERATE) {
        main { bans(api.server) }
    }

    patch<BansPatch, Bans>(
        "/server/bans", "Ban or pardon players and IP addresses", Scope.PLAYERS_MODERATE,
        description = "Banned players who are online get kicked.",
    ) { patch ->
        val ban = patch.ban.map { it to profile(it.player) }
        val pardon = patch.pardon.map { profile(it) }
        val banIps = patch.banIps.map { it to address(it.ip) }
        val pardonIps = patch.pardonIps.map(::address)
        auditTarget = buildList {
            patch.ban.forEach { add("ban ${it.player}") }
            patch.pardon.forEach { add("pardon $it") }
            patch.banIps.forEach { add("ban-ip ${it.ip}") }
            patch.pardonIps.forEach { add("pardon-ip $it") }
        }.joinToString(", ").ifEmpty { null }
        main {
            val players = api.server.playerList
            for ((request, profile) in ban) {
                val until = request.durationSeconds?.let(::expiry)
                players.bans.add(UserBanListEntry(profile, Date(), SOURCE, until, request.reason))
                players.getPlayer(profile.id())?.connection?.disconnect(Component.translatable("multiplayer.disconnect.banned"))
            }
            pardon.forEach { players.bans.remove(it) }
            for ((request, address) in banIps) {
                players.ipBans.add(IpBanListEntry(address, Date(), SOURCE, request.durationSeconds?.let(::expiry), request.reason))
                players.getPlayersWithAddress(address).forEach { it.connection.disconnect(Component.translatable("multiplayer.disconnect.kicked")) }
            }
            pardonIps.forEach { players.ipBans.remove(it) }
            bans(api.server)
        }
    }
}

private fun expiry(seconds: Long): Date {
    if (seconds <= 0) badRequest("durationSeconds must be positive")
    return Date(System.currentTimeMillis() + seconds * 1000)
}

private fun address(ip: String): String {
    // Only literal addresses: InetAddress.getByName would resolve host names over DNS.
    val literal = Regex("^[0-9.]+$|^[0-9a-fA-F:]+$")
    if (!literal.matches(ip)) badRequest("\"$ip\" isn't an IP address")
    return runCatching { java.net.InetAddress.getByName(ip).hostAddress }.getOrElse { badRequest("\"$ip\" isn't an IP address") }
}

/**
 * A complete profile for a name or UUID. Players who joined before resolve from the server's cache; anyone else costs
 * a Mojang lookup, which runs off the server thread.
 */
internal suspend fun ModCall.profile(id: String): NameAndId {
    val server = api.server
    main { server.findKnown(id) }?.takeIf { it.name != null }?.let { return NameAndId(it.uuid, it.name!!) }
    val uuid = uuidOrNull(id)
    val cache = server.services().nameToIdCache()
    (if (uuid != null) cache.get(uuid) else cache.get(id)).orElse(null)?.let { return it }
    val resolver = server.services().profileResolver()
    val found = withContext(Dispatchers.IO) {
        runCatching { if (uuid != null) resolver.fetchById(uuid) else resolver.fetchByName(id) }.getOrNull()?.orElse(null)
    } ?: notFound("player_not_found", "No Minecraft account \"$id\"")
    return NameAndId(found)
}

private fun ref(profile: NameAndId) = PlayerRef(profile.id().toString(), profile.name().takeIf { it.isNotEmpty() })

private fun whitelist(server: MinecraftServer) = Whitelist(
    enabled = server.playerList.isUsingWhitelist,
    enforced = server.isEnforceWhitelist,
    players = server.playerList.whiteList.entries.mapNotNull { it.user }.map(::ref).sortedBy { it.name?.lowercase() },
)

private fun operators(server: MinecraftServer) = Operators(server.playerList.ops.entries.mapNotNull { it.user }.map(::ref).sortedBy { it.name?.lowercase() })

private fun bans(server: MinecraftServer): Bans {
    val players = server.playerList.bans.entries.map {
        val profile = it.user
        BanView(profile?.name()?.takeIf(String::isNotEmpty) ?: profile?.id()?.toString() ?: "?", profile?.id()?.toString(), it.reason, it.source, it.created?.toInstant()?.toString(), it.expires?.toInstant()?.toString())
    }
    val ips = server.playerList.ipBans.entries.map {
        BanView(it.user ?: "?", null, it.reason, it.source, it.created?.toInstant()?.toString(), it.expires?.toInstant()?.toString())
    }
    return Bans(players.sortedBy { it.target.lowercase() }, ips.sortedBy { it.target })
}
