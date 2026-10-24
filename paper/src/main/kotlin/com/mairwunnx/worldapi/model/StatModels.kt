package com.mairwunnx.worldapi.model

import kotlinx.serialization.Serializable

@Serializable
data class BoardInfo(val id: String, val title: String, val unit: String? = null)

@Serializable
data class Leaderboard(
    val id: String,
    val title: String,
    val unit: String? = null,
    /** Everyone with a score above zero; entries past `limit` are cut. */
    val total: Int,
    val entries: List<LeaderboardEntry>,
    val at: String,
)

@Serializable
data class LeaderboardEntry(
    /** Tied players share a place; the next place skips ahead, as in 1, 1, 3. */
    val place: Int,
    val player: PlayerRef,
    val value: Double,
    val online: Boolean,
)

@Serializable
data class Aggregates(
    /** Players with any statistics. */
    val players: Int,
    val hours: Double,
    val blocks: Long,
    /** The block mined most, without its namespace: `stone`. */
    val topBlock: String? = null,
    val mobs: Long,
    val topMob: String? = null,
    val deaths: Long,
    val advancements: Long,
    /** The overworld's day number. */
    val day: Long,
    /** The first join WorldAPI recorded, or the earliest first-played date. */
    val since: String? = null,
    val at: String,
)
