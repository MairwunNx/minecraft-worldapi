package com.mairwunnx.worldapi.core

import com.mairwunnx.worldapi.model.LogLine
import java.time.Instant

/**
 * The latest console lines, kept in memory for `GET /server/logs`. Tokens never get in: the ones
 * in config.yml are replaced by name, and any long run of hex digits, which is what WorldAPI's
 * tokens look like, is blanked out. Lines arrive from any thread.
 */
class ConsoleLog(private val capacity: () -> Int, private val secrets: () -> Collection<String>) {
    private val lines = ArrayDeque<LogLine>()

    fun add(at: Long, level: String, logger: String?, message: String) {
        val line = LogLine(Instant.ofEpochMilli(at).toString(), level.lowercase(), logger?.takeIf { it.isNotEmpty() }, clean(message))
        synchronized(lines) {
            lines.addLast(line)
            val size = capacity().coerceAtLeast(1)
            while (lines.size > size) lines.removeFirst()
        }
    }

    /** The newest [limit] lines at [minLevel] or above that contain [grep], oldest first. */
    fun read(minLevel: String?, grep: String?, limit: Int): List<LogLine> {
        val floor = minLevel?.let { LEVELS.indexOf(it.lowercase()) } ?: 0
        val snapshot = synchronized(lines) { lines.toList() }
        return snapshot.asSequence()
            .filter { LEVELS.indexOf(it.level).coerceAtLeast(0) >= floor }
            .filter { grep == null || it.message.contains(grep, ignoreCase = true) || it.logger?.contains(grep, ignoreCase = true) == true }
            .toList()
            .takeLast(limit)
    }

    val size get() = synchronized(lines) { lines.size }

    private fun clean(message: String): String {
        var text = message.replace(ANSI, "").replace(LEGACY_COLOR, "")
        secrets().filter { it.length >= 8 }.forEach { text = text.replace(it, "[token]") }
        return text.replace(HEX_RUN, "[redacted]")
    }

    companion object {
        /** From least to most severe. */
        val LEVELS = listOf("trace", "debug", "info", "warn", "error", "fatal")

        private val ANSI = Regex("\u001B\\[[;\\d]*[A-Za-z]")
        private val LEGACY_COLOR = Regex("§[0-9a-fk-orx]", RegexOption.IGNORE_CASE)
        private val HEX_RUN = Regex("(?<![0-9A-Za-z])[0-9a-fA-F]{32,}(?![0-9A-Za-z])")
    }
}
