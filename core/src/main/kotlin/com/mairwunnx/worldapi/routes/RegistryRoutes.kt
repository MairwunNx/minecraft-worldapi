package com.mairwunnx.worldapi.routes

import com.mairwunnx.worldapi.api.Scope
import com.mairwunnx.worldapi.api.endpoints
import com.mairwunnx.worldapi.api.notFound
import com.mairwunnx.worldapi.api.page
import com.mairwunnx.worldapi.api.pageParams
import com.mairwunnx.worldapi.api.string
import com.mairwunnx.worldapi.model.RegistryEntry
import com.mairwunnx.worldapi.model.RegistryInfo

/** The game's registries a client may list, by their vanilla names; each platform reads them from its own game. */
val REGISTRIES = listOf(
    "item", "block", "entity_type", "mob_effect", "enchantment", "potion", "biome", "structure",
    "sound_event", "particle_type", "villager_profession", "attribute", "damage_type",
)

val registryRoutes = endpoints("Registries", Scope.SERVER_READ) {
    get<List<RegistryInfo>>("/registries", "The game's registries: items, blocks, entities, effects, and more, with how many keys each holds") {
        REGISTRIES.map { RegistryInfo(it, api.registryKeys(it).size) }
    }

    get(
        "/registries/{registry}", "Keys in one registry, such as every item or every biome",
        description = "Mods and data packs add their own keys. `search` takes words, so `oak log` finds `minecraft:oak_log` and `minecraft:stripped_oak_log`.",
        query = pageParams + string("search", "Only keys that hold every word of this, ignoring case"),
    ) {
        val registry = path("registry")
        if (registry !in REGISTRIES) notFound("registry_not_found", "No registry \"$registry\"; there are ${REGISTRIES.joinToString()}")
        val search = query("search")
        api.registryKeys(registry)
            .filter { search == null || registryMatches(it, search) }
            .sorted()
            .map(::RegistryEntry)
            .page(limit, cursor)
    }
}

/** Whether [key] holds every word of [search], so `oak log` finds `minecraft:stripped_oak_log`. */
fun registryMatches(key: String, search: String): Boolean =
    search.lowercase().split(' ', '_', ':', ',').filter { it.isNotEmpty() }.all { it in key }
