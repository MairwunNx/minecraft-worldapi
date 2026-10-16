package com.mairwunnx.worldapi.stats

import com.mairwunnx.worldapi.store.Store
import com.mairwunnx.worldapi.store.TamedAnimals
import com.mairwunnx.worldapi.store.TamedSeed
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.ExpressionWithColumnType
import org.jetbrains.exposed.v1.core.case
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import java.util.UUID

data class Tamer(val uuid: UUID, val name: String, val tamed: Long)

/**
 * Animals each player has tamed. Vanilla keeps no such statistic, so WorldAPI counts the pets
 * already in the world once, by their owners, and adds every taming after that. A pet that died
 * before that first count left no trace, so the board is a lower bound for the oldest players.
 */
class Tamed(private val store: Store) {
    @Volatile
    var seeded: Boolean = store.blocking { TamedSeed.selectAll().any() }
        private set

    /** Starts the counts from the pets in the world, by owner, unless that was done before. */
    fun seed(owners: Map<UUID, Pair<String, Long>>) {
        if (seeded) return
        seeded = true
        store.write {
            val fresh = TamedSeed.insertIgnore {
                it[id] = 1
                it[takenAt] = System.currentTimeMillis()
                it[pets] = owners.values.sumOf { (_, count) -> count }
            }.insertedCount == 1
            // Tamings counted while the world was read are in the pets too: the larger of the two, never the sum.
            if (fresh) owners.forEach { (uuid, value) -> upsert(uuid, value.first, value.second) { stored, incoming -> case().When(stored greater incoming, stored).Else(incoming) } }
        }
    }

    fun add(uuid: UUID, name: String) = store.write { upsert(uuid, name, 1) { stored, incoming -> stored + incoming } }

    suspend fun totals(): List<Tamer> = store.read {
        TamedAnimals.selectAll().map { Tamer(UUID.fromString(it[TamedAnimals.playerUuid]), it[TamedAnimals.playerName], it[TamedAnimals.tamed]) }
    }

    private fun JdbcTransaction.upsert(
        uuid: UUID,
        name: String,
        count: Long,
        merge: (stored: ExpressionWithColumnType<Long>, incoming: ExpressionWithColumnType<Long>) -> Expression<Long>,
    ) {
        TamedAnimals.upsert(onUpdate = {
            it[TamedAnimals.playerName] = insertValue(TamedAnimals.playerName)
            it[TamedAnimals.tamed] = merge(TamedAnimals.tamed, insertValue(TamedAnimals.tamed))
        }) {
            it[playerUuid] = uuid.toString()
            it[playerName] = name
            it[tamed] = count
        }
    }
}
