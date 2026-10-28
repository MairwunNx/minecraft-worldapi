package com.mairwunnx.worldapi.changes

import com.mairwunnx.worldapi.model.BlockPosition
import com.mairwunnx.worldapi.model.ChangeView
import java.time.Instant

/**
 * What the API changed in worlds, kept in memory until the server stops so a change can be undone. [S] is a platform's
 * copy of one block as it was: its state, and a block entity's data such as a chest's items. Changes past
 * limits.undoBlocks drop out, oldest first.
 */
class Changes<S>(private val capacity: () -> Int) {
    /** One block a change touched: where, what it was, and the state the change left there. */
    class Block<S>(val x: Int, val y: Int, val z: Int, val before: S, val after: String)

    class Change<S>(
        val id: Long,
        val at: Long,
        val by: String,
        val world: String,
        val what: String,
        val blocks: List<Block<S>>,
    ) {
        @Volatile
        var undone = false

        val from get() = BlockPosition(blocks.minOf { it.x }, blocks.minOf { it.y }, blocks.minOf { it.z })
        val to get() = BlockPosition(blocks.maxOf { it.x }, blocks.maxOf { it.y }, blocks.maxOf { it.z })

        /** The chunks its blocks lie in, as chunk x and z. */
        val chunks get() = blocks.map { (it.x shr 4) to (it.z shr 4) }.distinct()

        fun view() = ChangeView(id, Instant.ofEpochMilli(at).toString(), by, world, what, from, to, blocks.size, undone)
    }

    private val changes = ArrayDeque<Change<S>>()
    private var kept = 0
    private var nextId = 1L

    /** Keeps a change unless nothing changed or it alone passes the limit. Answers its id, or null when it wasn't kept. */
    @Synchronized
    fun record(by: String, world: String, what: String, blocks: List<Block<S>>, at: Long = System.currentTimeMillis()): Long? {
        val limit = capacity()
        if (blocks.isEmpty() || blocks.size > limit) return null
        while (kept + blocks.size > limit) kept -= changes.removeFirst().blocks.size
        val change = Change(nextId++, at, by, world, what, blocks)
        changes.addLast(change)
        kept += blocks.size
        return change.id
    }

    @Synchronized
    fun get(id: Long): Change<S>? = changes.firstOrNull { it.id == id }

    /** Newest first. */
    @Synchronized
    fun list(): List<Change<S>> = changes.reversed()
}
