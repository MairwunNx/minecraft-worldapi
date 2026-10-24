package com.mairwunnx.worldapi.stats

import com.mairwunnx.worldapi.core.MainThread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.stats.StatType
import net.minecraft.stats.StatsCounter
import net.minecraft.world.level.storage.LevelResource
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Reads statistics as Paper's `Statistics` does: online players from the game's live counters, offline players from
 * the stats files, cached until a file changes. The game names every statistic by its vanilla id already, so nothing
 * needs mapping here.
 */
class ModStatistics(private val server: MinecraftServer, private val main: MainThread) : StatsSource {
    private class Cached<T>(val modified: Long, val value: T)

    private val files = ConcurrentHashMap<String, Cached<*>>()
    private val json = Json { ignoreUnknownKeys = true }

    private fun folder(resource: LevelResource): File? = server.getWorldPath(resource).toFile().takeIf { it.isDirectory }

    suspend fun of(uuid: UUID): StatMap {
        val online = main.call { server.playerList.getPlayer(uuid)?.let(::live) }
        return online ?: withContext(Dispatchers.IO) { fromFile(uuid) }
    }

    suspend fun advancementsOf(uuid: UUID): Int {
        val online = main.call { server.playerList.getPlayer(uuid)?.let(::advancementsDone) }
        return online ?: withContext(Dispatchers.IO) { advancementsFromFile(uuid) }
    }

    override suspend fun everyone(refs: List<StatRef>?, withAdvancements: Boolean): List<PlayerStats> {
        val online = main.call {
            server.playerList.players.map { player ->
                PlayerStats(
                    player.uuid, player.gameProfile.name(), true,
                    refs?.let { read(player, it) } ?: live(player),
                    if (withAdvancements) advancementsDone(player) else 0,
                )
            }
        }
        val onlineIds = online.map { it.uuid }.toSet()
        val offline = withContext(Dispatchers.IO) {
            val names = server.services().nameToIdCache()
            (folder(LevelResource.PLAYER_STATS_DIR)?.listFiles { file -> file.name.endsWith(".json") } ?: emptyArray()).mapNotNull { file ->
                val uuid = runCatching { UUID.fromString(file.nameWithoutExtension) }.getOrNull() ?: return@mapNotNull null
                if (uuid in onlineIds) return@mapNotNull null
                val name = names.get(uuid).map { it.name() }.orElse(null) ?: return@mapNotNull null
                PlayerStats(uuid, name, false, fromFile(uuid), if (withAdvancements) advancementsFromFile(uuid) else 0)
            }
        }
        return online + offline
    }

    /** Every non-zero statistic of an online player. Must run on the server thread. */
    fun live(player: ServerPlayer): StatMap {
        val counter = player.stats
        val result = mutableMapOf<String, MutableMap<String, Long>>()
        for (type in BuiltInRegistries.STAT_TYPE) {
            val values = result.getOrPut(BuiltInRegistries.STAT_TYPE.getKey(type).toString()) { mutableMapOf() }
            collect(counter, type, values)
        }
        return result.filterValues { it.isNotEmpty() }
    }

    /** The statistics a type has made for any player: one nobody ever counted reads 0 for everyone. */
    private fun <T : Any> collect(counter: StatsCounter, type: StatType<T>, into: MutableMap<String, Long>) {
        for (stat in type) into.putIfPositive(type.registry.getKey(stat.value).toString(), counter.getValue(stat))
    }

    /** Just the statistics in [refs]. Must run on the server thread. */
    private fun read(player: ServerPlayer, refs: List<StatRef>): StatMap {
        val counter = player.stats
        val result = mutableMapOf<String, MutableMap<String, Long>>()
        for (ref in refs) {
            @Suppress("UNCHECKED_CAST")
            val type = BuiltInRegistries.STAT_TYPE.getValue(Identifier.tryParse(ref.category) ?: continue) as StatType<Any>? ?: continue
            val values = result.getOrPut(ref.category) { mutableMapOf() }
            if (ref.wildcard) {
                collect(counter, type, values)
            } else {
                val value = type.registry.getValue(Identifier.tryParse(ref.key) ?: continue) ?: continue
                values.putIfPositive(ref.key, counter.getValue(type, value))
            }
        }
        return result
    }

    private fun MutableMap<String, Long>.putIfPositive(key: String, value: Int) {
        if (value > 0) put(key, value.toLong())
    }

    /** Must run on the server thread. Recipes and tab roots don't count, as on the vanilla screen. */
    fun advancementsDone(player: ServerPlayer): Int {
        var done = 0
        for (holder in server.advancements.allAdvancements) {
            val key = holder.id().path
            if (!key.startsWith("recipes/") && !key.endsWith("/root") && player.advancements.getOrStartProgress(holder).isDone) done++
        }
        return done
    }

    fun fromFile(uuid: UUID): StatMap {
        val file = folder(LevelResource.PLAYER_STATS_DIR)?.let { File(it, "$uuid.json") }?.takeIf { it.isFile } ?: return emptyMap()
        return cached(file) {
            val root = json.parseToJsonElement(file.readText()).jsonObject["stats"]?.jsonObject ?: JsonObject(emptyMap())
            root.mapValues { (_, values) -> values.jsonObject.mapValues { it.value.jsonPrimitive.longOrNull ?: 0 } }
        }
    }

    /** The raw advancements file of an offline player, or null when there is none. */
    suspend fun advancementsFile(uuid: UUID): String? = withContext(Dispatchers.IO) {
        folder(LevelResource.PLAYER_ADVANCEMENTS_DIR)?.let { File(it, "$uuid.json") }?.takeIf { it.isFile }?.readText()
    }

    fun advancementsFromFile(uuid: UUID): Int {
        val file = folder(LevelResource.PLAYER_ADVANCEMENTS_DIR)?.let { File(it, "$uuid.json") }?.takeIf { it.isFile } ?: return 0
        return cached(file) { countDone(file.readText()) }
    }

    /** Finished advancements in a vanilla advancements file. */
    fun countDone(text: String): Int = json.parseToJsonElement(text).jsonObject.count { (key, value) ->
        val done = (value as? JsonObject)?.get("done")?.jsonPrimitive?.booleanOrNull == true
        done && "recipes/" !in key && !key.endsWith("/root")
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> cached(file: File, read: () -> T): T {
        val modified = file.lastModified()
        val hit = files[file.path]
        if (hit != null && hit.modified == modified) return hit.value as T
        return read().also { files[file.path] = Cached(modified, it) }
    }
}
