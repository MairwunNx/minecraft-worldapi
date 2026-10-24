package com.mairwunnx.worldapi.bukkit

import com.mairwunnx.worldapi.api.PaperCall
import com.mairwunnx.worldapi.api.notFound
import com.destroystokyo.paper.profile.PlayerProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A complete profile for a name or UUID. Players who joined before resolve from the server's
 * cache; anyone else costs a Mojang lookup, which runs off the server thread.
 */
suspend fun PaperCall.profile(id: String): PlayerProfile {
    val server = api.server
    val known = main { server.findKnown(id)?.playerProfile }
    if (known?.id != null && known.name != null) return known
    val uuid = uuidOrNull(id)
    val profile = if (uuid != null) server.createProfile(uuid) else server.createProfile(id)
    val found = withContext(Dispatchers.IO) { runCatching { profile.complete(false) }.getOrDefault(false) }
    if (!found || profile.id == null) notFound("player_not_found", "No Minecraft account \"$id\"")
    return profile
}
