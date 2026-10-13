package com.mairwunnx.worldapi.stats

import com.mairwunnx.worldapi.store.ExperienceBaseline
import com.mairwunnx.worldapi.store.ExperienceGained
import com.mairwunnx.worldapi.store.Store
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.ExpressionWithColumnType
import org.jetbrains.exposed.v1.core.case
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class Gained(val uuid: UUID, val name: String, val points: Long)

/**
 * Experience each player has gained in total. Vanilla keeps no such statistic, so WorldAPI starts
 * each player from the `XpTotal` the game already knows, once, and adds up every pickup after
 * that. Pickups collect in memory, since an XP farm hands out dozens a second, and go to the
 * store every few seconds and on shutdown.
 */
class Experience(private val store: Store) {
    private class Pending(val name: String, val points: Long)

    private val pending = ConcurrentHashMap<UUID, Pending>()

    /** Players whose baseline is taken or queued; the table behind it survives restarts. */
    private val baselined: MutableSet<UUID> = ConcurrentHashMap.newKeySet<UUID>().apply {
        addAll(store.blocking { ExperienceBaseline.select(ExperienceBaseline.playerUuid).map { UUID.fromString(it[ExperienceBaseline.playerUuid]) } })
    }

    fun hasBaseline(uuid: UUID): Boolean = uuid in baselined

    /**
     * Starts a player's total from [xpTotal], the `XpTotal` in their playerdata, unless their
     * baseline was taken before. It counts experience since the player's last death, so it's a
     * lower bound of what they really gathered.
     *
     * The total becomes max(stored, xpTotal), never the sum. WorldAPI 1.1.0 counted pickups with no
     * baseline, and `XpTotal` already includes every pickup it counted, so adding the two would
     * count those twice. When the player died since, `XpTotal` dropped and the stored count is the
     * better of the two.
     */
    fun seed(uuid: UUID, name: String, xpTotal: Int) {
        if (!baselined.add(uuid)) return
        store.write {
            try {
                val fresh = ExperienceBaseline.insertIgnore {
                    it[playerUuid] = uuid.toString()
                    it[ExperienceBaseline.xpTotal] = xpTotal.toLong()
                    it[takenAt] = System.currentTimeMillis()
                }.insertedCount == 1
                if (fresh && xpTotal > 0) {
                    // The larger of the two, as CASE, since SQLite has no GREATEST and the others no two-argument max.
                    upsert(uuid, name, xpTotal.toLong()) { stored, incoming -> case().When(stored greater incoming, stored).Else(incoming) }
                }
            } catch (error: Exception) {
                baselined.remove(uuid)
                throw error
            }
        }
    }

    /**
     * Seeds everyone with a playerdata file in [folder] and no baseline yet. [names] come from
     * the server's offline players; a file without a known name or with a broken body is skipped,
     * and that player gets their baseline when they next join. Returns how many were seeded.
     */
    fun seedFromFiles(folder: File, names: Map<UUID, String?>): Int {
        var seeded = 0
        (folder.listFiles { file -> file.name.endsWith(".dat") } ?: emptyArray()).forEach { file ->
            val uuid = runCatching { UUID.fromString(file.nameWithoutExtension) }.getOrNull() ?: return@forEach
            if (uuid in baselined) return@forEach
            val name = names[uuid] ?: return@forEach
            val xpTotal = PlayerData.xpTotal(file) ?: return@forEach
            seed(uuid, name, xpTotal)
            seeded++
        }
        return seeded
    }

    fun add(uuid: UUID, name: String, points: Int) {
        if (points <= 0) return
        pending.merge(uuid, Pending(name, points.toLong())) { old, new -> Pending(new.name, old.points + new.points) }
    }

    fun flush() {
        val batch = pending.keys.mapNotNull { uuid -> pending.remove(uuid)?.let { uuid to it } }
        if (batch.isEmpty()) return
        store.write {
            batch.forEach { (uuid, it) -> upsert(uuid, it.name, it.points) { stored, incoming -> stored + incoming } }
        }
    }

    /** Everyone's total, including pickups that haven't reached the store yet. */
    suspend fun totals(): List<Gained> {
        val stored = store.read {
            ExperienceGained.selectAll().map { Gained(UUID.fromString(it[ExperienceGained.playerUuid]), it[ExperienceGained.playerName], it[ExperienceGained.gained]) }
        }.associateBy { it.uuid }.toMutableMap()
        pending.forEach { (uuid, it) ->
            stored.merge(uuid, Gained(uuid, it.name, it.points)) { old, new -> Gained(uuid, new.name, old.points + new.points) }
        }
        return stored.values.toList()
    }

    /** Inserts a player's row, or renames it and sets its total to [merge] of the stored and the new points. */
    private fun JdbcTransaction.upsert(
        uuid: UUID,
        name: String,
        points: Long,
        merge: (stored: ExpressionWithColumnType<Long>, incoming: ExpressionWithColumnType<Long>) -> Expression<Long>,
    ) {
        ExperienceGained.upsert(onUpdate = {
            it[ExperienceGained.playerName] = insertValue(ExperienceGained.playerName)
            it[ExperienceGained.gained] = merge(ExperienceGained.gained, insertValue(ExperienceGained.gained))
        }) {
            it[playerUuid] = uuid.toString()
            it[playerName] = name
            it[gained] = points
        }
    }
}
