package com.mairwunnx.worldapi.stats

import com.mairwunnx.worldapi.store.Store
import com.mairwunnx.worldapi.store.bind
import com.mairwunnx.worldapi.store.rows
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
        addAll(store.blocking {
            prepareStatement("SELECT player_uuid FROM experience_baseline").use { it.executeQuery().rows { UUID.fromString(getString("player_uuid")) } }
        })
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
            autoCommit = false
            try {
                val fresh = prepareStatement("INSERT OR IGNORE INTO experience_baseline (player_uuid, xp_total, taken_at) VALUES (?, ?, ?)")
                    .use { it.bind(uuid.toString(), xpTotal.toLong(), System.currentTimeMillis()).executeUpdate() } == 1
                if (fresh && xpTotal > 0) {
                    prepareStatement(
                        """INSERT INTO experience (player_uuid, player_name, gained) VALUES (?, ?, ?)
                           ON CONFLICT(player_uuid) DO UPDATE SET player_name = excluded.player_name, gained = max(gained, excluded.gained)""",
                    ).use { it.bind(uuid.toString(), name, xpTotal.toLong()).executeUpdate() }
                }
                commit()
            } catch (error: Exception) {
                rollback()
                baselined.remove(uuid)
                throw error
            } finally {
                autoCommit = true
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
            prepareStatement(
                """INSERT INTO experience (player_uuid, player_name, gained) VALUES (?, ?, ?)
                   ON CONFLICT(player_uuid) DO UPDATE SET player_name = excluded.player_name, gained = gained + excluded.gained""",
            ).use { statement -> batch.forEach { (uuid, it) -> statement.bind(uuid.toString(), it.name, it.points).executeUpdate() } }
        }
    }

    /** Everyone's total, including pickups that haven't reached the store yet. */
    suspend fun totals(): List<Gained> {
        val stored = store.read {
            prepareStatement("SELECT player_uuid, player_name, gained FROM experience").use {
                it.executeQuery().rows { Gained(UUID.fromString(getString("player_uuid")), getString("player_name"), getLong("gained")) }
            }
        }.associateBy { it.uuid }.toMutableMap()
        pending.forEach { (uuid, it) ->
            stored.merge(uuid, Gained(uuid, it.name, it.points)) { old, new -> Gained(uuid, new.name, old.points + new.points) }
        }
        return stored.values.toList()
    }
}
