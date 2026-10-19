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

    /** Gets every cleaned line as it arrives, for the server.console topic. */
    @Volatile
    var listener: ((LogLine) -> Unit)? = null

    // Whatever the listener logs would come straight back here; one line at a time per thread.
    private val inListener = ThreadLocal.withInitial { false }

    /** Lines logged while a console command runs; see [capture]. */
    class Capture(val thread: String, val since: Long) {
        val lines = java.util.concurrent.CopyOnWriteArrayList<String>()
        @Volatile var until = Long.MAX_VALUE
    }

    private val captures = java.util.concurrent.CopyOnWriteArrayList<Capture>()

    /**
     * Collects what the server logs from now until [Capture.until] and a moment after, cleaned
     * like every line. A command run as the console answers in the log, so this is its output;
     * some of it, like `say`, comes from other threads. Read [Capture.lines] a moment after the
     * command returns, then [release].
     */
    fun capture(): Capture = Capture(Thread.currentThread().name, System.currentTimeMillis()).also(captures::add)

    fun release(capture: Capture) = captures.remove(capture)

    fun add(at: Long, level: String, logger: String?, message: String, thread: String? = null) {
        val line = LogLine(Instant.ofEpochMilli(at).toString(), level.lowercase(), logger?.takeIf { it.isNotEmpty() }, clean(message))
        // The command's own thread counts whenever; other threads (chat) by time. Log4j's clock may lag a little.
        captures.forEach {
            val own = thread == it.thread && it.until == Long.MAX_VALUE
            if (own || at in (it.since - GRACE_MILLIS)..(it.until.coerceAtMost(Long.MAX_VALUE - GRACE_MILLIS) + GRACE_MILLIS)) it.lines += line.message.replace(CHAT_PREFIX, "")
        }
        synchronized(lines) {
            lines.addLast(line)
            val size = capacity().coerceAtLeast(1)
            while (lines.size > size) lines.removeFirst()
        }
        val listener = listener ?: return
        if (inListener.get()) return
        inListener.set(true)
        try {
            listener(line)
        } finally {
            inListener.set(false)
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
        private const val GRACE_MILLIS = 250L

        /** What Paper puts before chat in the console, which a command's output doesn't need. */
        private val CHAT_PREFIX = Regex("^(System chat: |\\[Not Secure] )+")

        /** From least to most severe. */
        val LEVELS = listOf("trace", "debug", "info", "warn", "error", "fatal")

        private val ANSI = Regex("\u001B\\[[;\\d]*[A-Za-z]")
        private val LEGACY_COLOR = Regex("§[0-9a-fk-orx]", RegexOption.IGNORE_CASE)
        private val HEX_RUN = Regex("(?<![0-9A-Za-z])[0-9a-fA-F]{32,}(?![0-9A-Za-z])")
    }
}
