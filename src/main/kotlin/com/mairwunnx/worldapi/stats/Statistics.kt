package com.mairwunnx.worldapi.stats

import com.mairwunnx.worldapi.core.MainThread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.bukkit.Material
import org.bukkit.OfflinePlayer
import org.bukkit.Server
import org.bukkit.Statistic
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** One player's statistics and finished advancements, live when they are online. */
data class PlayerStats(val uuid: UUID, val name: String, val online: Boolean, val stats: StatMap, val advancements: Int)

/**
 * Reads statistics. Online players come from the server's live counters, so numbers move as
 * people play instead of waiting for the next autosave; offline players come from the stats
 * files, cached until the file changes.
 */
class Statistics(private val server: Server, private val main: MainThread) {
    private class Cached<T>(val modified: Long, val value: T)

    private val files = ConcurrentHashMap<String, Cached<*>>()
    private val json = Json { ignoreUnknownKeys = true }

    private val blocks by lazy { Material.entries.filter { !it.isLegacy && it.isBlock && !it.isAir } }
    private val items by lazy { Material.entries.filter { !it.isLegacy && it.isItem && !it.isAir } }
    private val entities by lazy { EntityType.entries.filter { it != EntityType.UNKNOWN && it.isAlive } }

    /** Where vanilla keeps per-player files: `<level>/players/stats` since 26.1, `<level>/stats` before. */
    private fun folder(kind: String): File? {
        val level = server.worlds.firstOrNull()?.worldFolder ?: return null
        val candidates = generateSequence(level) { it.parentFile }.take(4).flatMap { sequenceOf(File(it, "players/$kind"), File(it, kind)) }
        return candidates.firstOrNull { it.isDirectory }
    }

    suspend fun of(player: OfflinePlayer): StatMap {
        val online = main.call { server.getPlayer(player.uniqueId)?.let(::live) }
        return online ?: withContext(Dispatchers.IO) { fromFile(player.uniqueId) }
    }

    suspend fun advancementsOf(player: OfflinePlayer): Int {
        val online = main.call { server.getPlayer(player.uniqueId)?.let(::advancementsDone) }
        return online ?: withContext(Dispatchers.IO) { advancementsFromFile(player.uniqueId) }
    }

    /**
     * Everyone who has a stats file or is online. [refs] limits the live read to the statistics
     * a caller needs; a leaderboard asks for one or two, not all eight thousand.
     */
    suspend fun everyone(refs: List<StatRef>?, withAdvancements: Boolean): List<PlayerStats> {
        val online = main.call {
            server.onlinePlayers.map { player ->
                PlayerStats(
                    player.uniqueId, player.name, true,
                    refs?.let { read(player, it) } ?: live(player),
                    if (withAdvancements) advancementsDone(player) else 0,
                )
            }
        }
        val onlineIds = online.map { it.uuid }.toSet()
        val offline = withContext(Dispatchers.IO) {
            val names = server.offlinePlayers.associate { it.uniqueId to it.name }
            (folder("stats")?.listFiles { file -> file.name.endsWith(".json") } ?: emptyArray()).mapNotNull { file ->
                val uuid = runCatching { UUID.fromString(file.nameWithoutExtension) }.getOrNull() ?: return@mapNotNull null
                if (uuid in onlineIds) return@mapNotNull null
                val name = names[uuid] ?: return@mapNotNull null
                PlayerStats(uuid, name, false, fromFile(uuid), if (withAdvancements) advancementsFromFile(uuid) else 0)
            }
        }
        return online + offline
    }

    /** Every non-zero statistic of an online player. Must run on the server thread. */
    fun live(player: Player): StatMap {
        val result = mutableMapOf<String, MutableMap<String, Long>>()
        for (statistic in Statistic.entries) {
            val category = category(statistic)
            val values = result.getOrPut(category) { mutableMapOf() }
            when (statistic.type) {
                Statistic.Type.UNTYPED -> values.putIfPositive(statistic.key.asString(), player.getStatistic(statistic))
                Statistic.Type.BLOCK -> blocks.forEach { values.putIfPositive(it.key.asString(), player.getStatistic(statistic, it)) }
                Statistic.Type.ITEM -> items.forEach { values.putIfPositive(it.key.asString(), player.getStatistic(statistic, it)) }
                Statistic.Type.ENTITY -> entities.forEach {
                    values.putIfPositive(it.key.asString(), runCatching { player.getStatistic(statistic, it) }.getOrDefault(0))
                }
            }
        }
        return result.filterValues { it.isNotEmpty() }
    }

    /** Just the statistics in [refs]. Must run on the server thread. */
    private fun read(player: Player, refs: List<StatRef>): StatMap {
        val result = mutableMapOf<String, MutableMap<String, Long>>()
        for (ref in refs) {
            val statistic = statisticOf(ref) ?: continue
            val values = result.getOrPut(ref.category) { mutableMapOf() }
            val keys: List<Pair<String, () -> Int>> = when (statistic.type) {
                Statistic.Type.UNTYPED -> listOf(ref.key to { player.getStatistic(statistic) })
                Statistic.Type.BLOCK -> materials(ref, blocks).map { m -> m.key.asString() to { player.getStatistic(statistic, m) } }
                Statistic.Type.ITEM -> materials(ref, items).map { m -> m.key.asString() to { player.getStatistic(statistic, m) } }
                Statistic.Type.ENTITY -> entityTypes(ref).map { e -> e.key.asString() to { runCatching { player.getStatistic(statistic, e) }.getOrDefault(0) } }
            }
            keys.forEach { (key, value) -> values.putIfPositive(key, value()) }
        }
        return result
    }

    private fun materials(ref: StatRef, all: List<Material>) =
        if (ref.wildcard) all else listOfNotNull(Material.matchMaterial(ref.key)?.takeIf { it in all })

    private fun entityTypes(ref: StatRef) =
        if (ref.wildcard) entities else listOfNotNull(entities.firstOrNull { it.key.asString() == ref.key })

    private fun statisticOf(ref: StatRef): Statistic? = when (ref.category) {
        "minecraft:custom" -> Statistic.entries.firstOrNull { it.type == Statistic.Type.UNTYPED && it.key.asString() == ref.key }
        else -> Statistic.entries.firstOrNull { it.type != Statistic.Type.UNTYPED && category(it) == ref.category }
    }

    private fun MutableMap<String, Long>.putIfPositive(key: String, value: Int) {
        if (value > 0) put(key, value.toLong())
    }

    /** Must run on the server thread. Recipes and tab roots don't count, as on the vanilla screen. */
    fun advancementsDone(player: Player): Int {
        var done = 0
        server.advancementIterator().forEach {
            val key = it.key.key
            if (!key.startsWith("recipes/") && !key.endsWith("/root") && player.getAdvancementProgress(it).isDone) done++
        }
        return done
    }

    fun fromFile(uuid: UUID): StatMap {
        val file = folder("stats")?.let { File(it, "$uuid.json") }?.takeIf { it.isFile } ?: return emptyMap()
        return cached(file) {
            val root = json.parseToJsonElement(file.readText()).jsonObject["stats"]?.jsonObject ?: JsonObject(emptyMap())
            root.mapValues { (_, values) -> values.jsonObject.mapValues { it.value.jsonPrimitive.longOrNull ?: 0 } }
        }
    }

    /** The raw advancements file of an offline player, or null when there is none. */
    suspend fun advancementsFile(uuid: UUID): String? = withContext(Dispatchers.IO) {
        folder("advancements")?.let { File(it, "$uuid.json") }?.takeIf { it.isFile }?.readText()
    }

    fun advancementsFromFile(uuid: UUID): Int {
        val file = folder("advancements")?.let { File(it, "$uuid.json") }?.takeIf { it.isFile } ?: return 0
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

    companion object {
        fun category(statistic: Statistic): String = when (statistic) {
            Statistic.MINE_BLOCK -> "minecraft:mined"
            Statistic.BREAK_ITEM -> "minecraft:broken"
            Statistic.CRAFT_ITEM -> "minecraft:crafted"
            Statistic.USE_ITEM -> "minecraft:used"
            Statistic.PICKUP -> "minecraft:picked_up"
            Statistic.DROP -> "minecraft:dropped"
            Statistic.KILL_ENTITY -> "minecraft:killed"
            Statistic.ENTITY_KILLED_BY -> "minecraft:killed_by"
            else -> "minecraft:custom"
        }
    }
}
