package com.mairwunnx.worldapi.game

import com.mairwunnx.worldapi.api.badRequest
import com.mairwunnx.worldapi.api.notFound
import com.mairwunnx.worldapi.model.LocationView
import com.mairwunnx.worldapi.model.Position
import net.minecraft.core.BlockPos
import net.minecraft.core.GlobalPos
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
import kotlin.math.round

private fun Double.short() = round(this * 100) / 100

/** Where an entity stands, as Paper's `Location.view` shows it. */
fun Entity.view(withRotation: Boolean = true) = LocationView(
    world = (level() as? ServerLevel)?.worldName ?: "unknown",
    x = x.short(), y = y.short(), z = z.short(),
    yaw = if (withRotation) yRot else null,
    pitch = if (withRotation) xRot else null,
)

fun Entity.position() = Position(x.short(), y.short(), z.short())

/** A block's corner in a world, as Bukkit's block location reads it. */
fun GlobalPos.view(server: MinecraftServer): LocationView? {
    val level = server.getLevel(dimension()) ?: return null
    return LocationView(level.worldName, pos().x.toDouble(), pos().y.toDouble(), pos().z.toDouble())
}

/** A place in a request: a world, a point, and a direction. */
class Target(val level: ServerLevel, val pos: Vec3, val yaw: Float, val pitch: Float) {
    val block: BlockPos get() = BlockPos.containing(pos)
}

/** Turns a location in a request into a place in a world; a missing world means [fallback]. */
fun LocationView.toTarget(server: MinecraftServer, fallback: ServerLevel? = null): Target {
    val level = server.findWorld(world) ?: fallback ?: notFound("world_not_found", "No world \"${this.world}\"")
    if (!x.isFinite() || !y.isFinite() || !z.isFinite()) badRequest("Coordinates must be finite numbers")
    return Target(level, Vec3(x, y, z), yaw ?: 0f, pitch ?: 0f)
}
