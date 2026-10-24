package com.mairwunnx.worldapi.routes

import com.mairwunnx.worldapi.api.badRequest
import com.mairwunnx.worldapi.model.ServerSpawnPatch

/** Checks a spawn change against the world's height before anything moves. */
fun checkSpawn(patch: ServerSpawnPatch, minHeight: Int, maxHeight: Int) {
    val coordinates = listOf(patch.x, patch.y, patch.z)
    if (coordinates.any { it != null } && coordinates.any { it == null }) badRequest("x, y, and z come together")
    patch.y?.let { if (it !in minHeight until maxHeight) badRequest("y must be between $minHeight and ${maxHeight - 1} in this world") }
    listOfNotNull(patch.x, patch.z).forEach { if (it !in -29_999_984..29_999_984) badRequest("x and z must lie within the world's 29999984 blocks") }
    patch.yaw?.let { if (!it.isFinite() || it !in -180f..180f) badRequest("yaw must be between -180 and 180") }
    patch.pitch?.let { if (!it.isFinite() || it !in -90f..90f) badRequest("pitch must be between -90 and 90") }
    patch.respawnRadius?.let { if (it < 0) badRequest("respawnRadius can't be negative") }
}

/** The moon's phases in the order the game counts them, from the full moon. */
val moonPhases = listOf("full_moon", "waning_gibbous", "third_quarter", "waning_crescent", "new_moon", "waxing_crescent", "first_quarter", "waxing_gibbous")

/** Ticks from a time of day until 13000, when night falls; 0 while it's night. */
fun ticksUntilNight(time: Long): Long = if (time in 13_000 until 23_000) 0 else Math.floorMod(13_000 - time, 24_000L)

/** When beds work on the vanilla clock: the sky is dark enough from 12542 in clear weather, from 12010 in rain. */
fun canSleep(time: Long, raining: Boolean, thundering: Boolean): Boolean = when {
    thundering -> true
    raining -> time in 12_010..23_991
    else -> time in 12_542..23_459
}
