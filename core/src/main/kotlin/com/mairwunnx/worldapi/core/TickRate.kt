package com.mairwunnx.worldapi.core

/**
 * Ticks per second over the last 1, 5, and 15 minutes, as Paper keeps them (Spigot's rolling averages): every 20 ticks
 * add how fast those 20 went, weighted by how long they took. For a platform whose server keeps no such numbers. A gap
 * longer than [PAUSE_NANOS], such as a paused singleplayer game, starts a new section instead of counting as lag.
 */
class TickRate(private val clock: () -> Long = System::nanoTime) {
    private class Rolling(private val size: Int) {
        private val samples = DoubleArray(size) { MAX }
        private val times = LongArray(size) { SECOND }
        private var total = MAX * SECOND * size
        private var index = 0

        fun add(tps: Double, time: Long) {
            total -= samples[index] * times[index]
            samples[index] = tps
            times[index] = time
            total += tps * time
            index = (index + 1) % size
        }

        val average: Double get() = total / (SECOND.toDouble() * size)
    }

    private val minute = Rolling(60)
    private val five = Rolling(300)
    private val fifteen = Rolling(900)
    private var ticks = 0
    private var section = clock()

    /** At the end of every server tick, on the server thread. */
    fun tick() {
        if (++ticks % 20 != 0) return
        val now = clock()
        val elapsed = now - section
        section = now
        if (elapsed <= 0 || elapsed > PAUSE_NANOS) return
        val tps = SECOND.toDouble() / elapsed * 20
        listOf(minute, five, fifteen).forEach { it.add(tps, elapsed) }
    }

    /** The 1, 5, and 15 minute averages, which may read a little over 20 after a slow stretch. */
    fun averages(): DoubleArray = doubleArrayOf(minute.average, five.average, fifteen.average)

    private companion object {
        const val MAX = 20.0
        const val SECOND = 1_000_000_000L
        const val PAUSE_NANOS = 5 * SECOND
    }
}
