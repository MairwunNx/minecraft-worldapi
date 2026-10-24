package com.mairwunnx.worldapi.stats

import com.mairwunnx.worldapi.model.WorldStats
import java.io.File
import java.io.RandomAccessFile
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.round

/** How much of a world exists on disk, from its region files; counted at most once a minute per world. */
object RegionStats {
    private val cache = ConcurrentHashMap<String, Pair<Long, WorldStats>>()

    /** Off the server thread: it reads every region file's header. [region] is the world's `region` folder. */
    fun of(name: String, region: File?): WorldStats {
        cache[name]?.takeIf { System.currentTimeMillis() - it.first < 60_000 }?.let { return it.second }
        val files = region?.listFiles { file -> file.name.endsWith(".mca") }?.toList() ?: emptyList()
        val chunks = files.sumOf(::chunksIn)
        val size = region?.parentFile?.walkTopDown()?.filter { it.isFile }?.sumOf { it.length() } ?: 0
        return WorldStats(name, chunks, round(chunks * 256 / 1e6 * 1000) / 1000, files.size, size, Instant.now().toString())
            .also { cache[name] = System.currentTimeMillis() to it }
    }

    /** A region file starts with 1024 four-byte offsets; a non-zero one means the chunk exists. */
    private fun chunksIn(file: File): Long = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < 4096) return@use 0L
            val header = ByteArray(4096).also(raf::readFully)
            (0 until 1024).count { i -> header[i * 4].toInt() or header[i * 4 + 1].toInt() or header[i * 4 + 2].toInt() or header[i * 4 + 3].toInt() != 0 }.toLong()
        }
    }.getOrDefault(0)
}
