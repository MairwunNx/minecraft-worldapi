package com.mairwunnx.worldapi.game

import com.mairwunnx.worldapi.api.ModCall
import com.mairwunnx.worldapi.api.notFound
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level

/**
 * Worlds on the mods are the game's dimensions in one save. A world's name is its dimension: `overworld`,
 * `the_nether`, `the_end`, and a mod's own as `namespace:path`; its key is the full id, as on Paper.
 */
val ServerLevel.worldName: String get() = dimension().identifier().let { if (it.namespace == Identifier.DEFAULT_NAMESPACE) it.path else it.toString() }

val ServerLevel.worldKey: String get() = dimension().identifier().toString()

/** Paper's names for the vanilla dimensions: the level name, then `_nether` and `_the_end`. */
private fun MinecraftServer.paperNames(): Map<String, ServerLevel?> {
    val base = worldData.levelName.lowercase()
    val overworld = getLevel(Level.OVERWORLD)
    val nether = getLevel(Level.NETHER)
    val end = getLevel(Level.END)
    return mapOf("world" to overworld, "world_nether" to nether, "world_the_end" to end, base to overworld, "${base}_nether" to nether, "${base}_the_end" to end)
}

/** A world by its name, its key, or the name Paper gives it (`world_nether`), so clients made for Paper find it too. */
fun MinecraftServer.findWorld(id: String): ServerLevel? {
    val wanted = id.lowercase()
    allLevels.firstOrNull { it.worldName == wanted || it.worldKey == wanted }?.let { return it }
    Identifier.tryParse(wanted)?.let { key -> allLevels.firstOrNull { it.dimension().identifier() == key } }?.let { return it }
    return paperNames()[wanted]
}

fun ModCall.world(id: String = path("world")): ServerLevel =
    api.server.findWorld(id) ?: notFound("world_not_found", "No world \"$id\"")

/** The worlds in the order Paper lists them: the overworld, the Nether, the End, then the rest by id. */
val MinecraftServer.worlds: List<ServerLevel>
    get() {
        val order = listOf(Level.OVERWORLD, Level.NETHER, Level.END)
        return allLevels.sortedWith(compareBy<ServerLevel> { order.indexOf(it.dimension()).let { i -> if (i < 0) order.size else i } }.thenBy { it.worldKey })
    }
