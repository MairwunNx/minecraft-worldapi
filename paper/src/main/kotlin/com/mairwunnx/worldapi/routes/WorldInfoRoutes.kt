package com.mairwunnx.worldapi.routes

import com.mairwunnx.worldapi.api.Scope
import com.mairwunnx.worldapi.api.badRequest
import com.mairwunnx.worldapi.api.boolean
import com.mairwunnx.worldapi.api.integer
import com.mairwunnx.worldapi.api.notFound
import com.mairwunnx.worldapi.api.string
import com.mairwunnx.worldapi.bukkit.world
import com.mairwunnx.worldapi.model.BlockPosition
import com.mairwunnx.worldapi.stats.RegionStats
import io.papermc.paper.registry.RegistryAccess
import io.papermc.paper.registry.RegistryKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bukkit.Location
import org.bukkit.World
import java.io.File

val worldInfoRoutes = endpoints("Worlds", Scope.SERVER_READ) {
    get("/worlds/{world}/stats", "Explored area, region files, and size on disk") {
        val (name, region) = main { world().let { it.name to regionFolder(it) } }
        withContext(Dispatchers.IO) { RegionStats.of(name, region) }
    }
}

/** The region folder of a dimension; its place moved between versions. */
private fun regionFolder(world: World): File? {
    val root = world.worldFolder
    return listOf("region", "DIM-1/region", "DIM1/region").map { File(root, it) }.firstOrNull { it.isDirectory }
}
