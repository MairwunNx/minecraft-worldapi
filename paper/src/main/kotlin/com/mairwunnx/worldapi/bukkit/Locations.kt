package com.mairwunnx.worldapi.bukkit

import com.mairwunnx.worldapi.api.PaperCall
import com.mairwunnx.worldapi.api.badRequest
import com.mairwunnx.worldapi.api.notFound
import com.mairwunnx.worldapi.model.LocationView
import com.mairwunnx.worldapi.model.Position
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.Server
import org.bukkit.World
import kotlin.math.round

private fun Double.short() = round(this * 100) / 100

fun Location.view(withRotation: Boolean = true) = LocationView(
    world = world?.name ?: "unknown",
    x = x.short(), y = y.short(), z = z.short(),
    yaw = if (withRotation) yaw else null,
    pitch = if (withRotation) pitch else null,
)

fun Location.position() = Position(x.short(), y.short(), z.short())

/** A world by its folder name (`world_nether`) or its key (`minecraft:the_nether`). */
fun Server.findWorld(id: String): World? =
    getWorld(id) ?: NamespacedKey.fromString(id.lowercase())?.let { getWorld(it) }

fun PaperCall.world(id: String = path("world")): World =
    api.server.findWorld(id) ?: notFound("world_not_found", "No world \"$id\"")

/** Turns a location in a request into a Bukkit location; a missing world means [fallback]. */
fun LocationView.toLocation(server: Server, fallback: World? = null): Location {
    val world = server.findWorld(world) ?: fallback ?: notFound("world_not_found", "No world \"${this.world}\"")
    if (!x.isFinite() || !y.isFinite() || !z.isFinite()) badRequest("Coordinates must be finite numbers")
    return Location(world, x, y, z, yaw ?: 0f, pitch ?: 0f)
}
