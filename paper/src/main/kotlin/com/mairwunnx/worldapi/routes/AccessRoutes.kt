package com.mairwunnx.worldapi.routes

import com.mairwunnx.worldapi.api.Scope
import com.mairwunnx.worldapi.api.badRequest
import com.mairwunnx.worldapi.bukkit.profile
import com.mairwunnx.worldapi.bukkit.ref
import com.mairwunnx.worldapi.model.BanView
import com.mairwunnx.worldapi.model.Bans
import com.mairwunnx.worldapi.model.BansPatch
import com.mairwunnx.worldapi.model.Operators
import com.mairwunnx.worldapi.model.OperatorsPatch
import com.mairwunnx.worldapi.model.Whitelist
import com.mairwunnx.worldapi.model.WhitelistPatch
import com.destroystokyo.paper.profile.PlayerProfile
import io.papermc.paper.ban.BanListType
import org.bukkit.BanEntry
import org.bukkit.Server
import java.net.InetAddress
import java.time.Duration

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
            val server = api.server
            patch.enabled?.let(server::setWhitelist)
            patch.enforced?.let(server::setWhitelistEnforced)
            add.forEach { server.getOfflinePlayer(it.id!!).isWhitelisted = true }
            remove.forEach { server.getOfflinePlayer(it.id!!).isWhitelisted = false }
            whitelist(server)
        }
    }

    get("/server/ops", "Server operators", scope = Scope.PLAYERS_MODERATE) {
        main { Operators(api.server.operators.map { it.ref() }.sortedBy { it.name?.lowercase() }) }
    }

    // An operator runs every command and /worldapi, which makes tokens with any permission: as much as the console.
    patch<OperatorsPatch, Operators>(
        "/server/ops", "Make players operators or take it away", Scope.SERVER_CONSOLE,
        description = "Needs `server:console`, not `players:moderate`: an operator may run every command, `/worldapi` among them.",
    ) { patch ->
        val add = patch.add.map { profile(it) }
        val remove = patch.remove.map { profile(it) }
        auditTarget = (patch.add.map { "+$it" } + patch.remove.map { "-$it" }).joinToString(" ").ifEmpty { null }
        main {
            add.forEach { api.server.getOfflinePlayer(it.id!!).isOp = true }
            remove.forEach { api.server.getOfflinePlayer(it.id!!).isOp = false }
            Operators(api.server.operators.map { it.ref() }.sortedBy { it.name?.lowercase() })
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
            val server = api.server
            val profiles = server.getBanList(BanListType.PROFILE)
            val ips = server.getBanList(BanListType.IP)
            for ((request, profile) in ban) {
                val duration = request.durationSeconds?.let(::duration)
                val online = server.getPlayer(profile.id!!)
                if (online != null) {
                    online.ban<BanEntry<PlayerProfile>>(request.reason, duration, SOURCE, true)
                } else {
                    profiles.addBan(profile, request.reason, duration, SOURCE)
                }
            }
            pardon.forEach { profiles.pardon(it) }
            for ((request, address) in banIps) {
                ips.addBan(address, request.reason, request.durationSeconds?.let(::duration), SOURCE)
                server.onlinePlayers.filter { it.address?.address == address }.forEach { it.kick() }
            }
            pardonIps.forEach { ips.pardon(it) }
            bans(server)
        }
    }
}

private fun duration(seconds: Long): Duration {
    if (seconds <= 0) badRequest("durationSeconds must be positive")
    return Duration.ofSeconds(seconds)
}

private fun address(ip: String): InetAddress {
    // Only literal addresses: InetAddress.getByName would resolve host names over DNS.
    val literal = Regex("^[0-9.]+$|^[0-9a-fA-F:]+$")
    if (!literal.matches(ip)) badRequest("\"$ip\" isn't an IP address")
    return runCatching { InetAddress.getByName(ip) }.getOrElse { badRequest("\"$ip\" isn't an IP address") }
}

private fun whitelist(server: Server) = Whitelist(
    enabled = server.hasWhitelist(),
    enforced = server.isWhitelistEnforced,
    players = server.whitelistedPlayers.map { it.ref() }.sortedBy { it.name?.lowercase() },
)

private fun bans(server: Server): Bans {
    val players = server.getBanList(BanListType.PROFILE).getEntries<BanEntry<PlayerProfile>>().map {
        val profile = it.banTarget
        BanView(profile.name ?: it.target, profile.id?.toString(), it.reason, it.source, it.created?.toInstant()?.toString(), it.expiration?.toInstant()?.toString())
    }
    val ips = server.getBanList(BanListType.IP).getEntries<BanEntry<InetAddress>>().map {
        BanView(it.target, null, it.reason, it.source, it.created?.toInstant()?.toString(), it.expiration?.toInstant()?.toString())
    }
    return Bans(players.sortedBy { it.target.lowercase() }, ips.sortedBy { it.target })
}
