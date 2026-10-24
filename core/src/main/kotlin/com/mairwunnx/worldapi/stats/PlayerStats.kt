package com.mairwunnx.worldapi.stats

import java.util.UUID

/** One player's statistics and finished advancements, live when they are online. */
data class PlayerStats(val uuid: UUID, val name: String, val online: Boolean, val stats: StatMap, val advancements: Int)

/** Where leaderboards and aggregates read everyone's statistics: live for online players, from the stats files for the rest. */
interface StatsSource {
    /**
     * Everyone who has a stats file or is online. [refs] limits the live read to the statistics a caller needs; a
     * leaderboard asks for one or two, not all eight thousand.
     */
    suspend fun everyone(refs: List<StatRef>?, withAdvancements: Boolean): List<PlayerStats>
}
