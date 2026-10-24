package com.mairwunnx.worldapi.files

import com.mairwunnx.worldapi.api.ApiException
import com.mairwunnx.worldapi.api.badRequest
import com.mairwunnx.worldapi.api.conflict
import com.mairwunnx.worldapi.api.notFound
import io.ktor.http.HttpStatusCode
import java.io.BufferedReader
import java.io.InputStream
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.HexFormat
import java.util.zip.GZIPInputStream
import kotlin.io.path.extension
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/**
 * The server folder as `/files` sees it: which paths a client may read or write, and the reading
 * and writing itself. Paths are relative to [root] with forward slashes. Nothing outside the root
 * is reachable, a symlink pointing out of it included, and WorldAPI's own folder, which holds the
 * tokens and the database, never is.
 */
class ServerFiles(root: Path, private val ownFolder: Path, private val backups: Path) {
    val root: Path = root.toAbsolutePath().normalize()
    private val realRoot: Path = runCatching { this.root.toRealPath() }.getOrDefault(this.root)

    enum class Access { READ, WRITE }

    /** Resolves a client's path and checks it may be touched at all. Throws 400 or 403. */
    fun resolve(path: String, access: Access): Path {
        val clean = path.trim().replace('\\', '/').trim('/')
        if (clean.isEmpty()) return root
        if ('\u0000' in clean || clean.split('/').any { it == ".." }) badRequest("path must stay inside the server folder", "invalid_path")
        val resolved = root.resolve(clean).normalize()
        if (!resolved.startsWith(root)) badRequest("path must stay inside the server folder", "invalid_path")
        // The nearest existing ancestor decides where a symlink really leads.
        val existing = generateSequence(resolved) { it.parent }.firstOrNull { Files.exists(it, LinkOption.NOFOLLOW_LINKS) } ?: root
        val real = runCatching { existing.toRealPath() }.getOrElse { existing }
        if (!real.startsWith(realRoot)) forbidden("$clean leads outside the server folder")
        if (denied(resolved) || (real != existing && denied(realRoot.relativize(real).let(root::resolve)))) forbidden("WorldAPI doesn't share $clean")
        return resolved
    }

    fun relative(path: Path): String = root.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/')

    /** Whether a path is off limits whatever the access: WorldAPI's folder, hidden files, keys and secrets. */
    fun denied(path: Path): Boolean {
        val normal = path.toAbsolutePath().normalize()
        if (normal.startsWith(ownFolder.toAbsolutePath().normalize()) || normal.startsWith(backups.toAbsolutePath().normalize())) return true
        val parts = root.relativize(normal).map { it.toString() }
        if (parts.any { it.startsWith(".") && it != "." }) return true
        val name = normal.name.lowercase()
        return name in SECRET_NAMES || SECRET_EXTENSIONS.any { name.endsWith(it) }
    }

    /** Whether a file's kind may be read, or written; folders are always listable unless [denied]. */
    fun allows(path: Path, access: Access): Boolean {
        if (denied(path)) return false
        val name = path.name.lowercase()
        val relative = relative(path)
        return when (access) {
            Access.READ -> name.endsWith(".log.gz") || name.substringAfterLast('.', "") in READABLE
            Access.WRITE -> readOnlyReason(path) == null
        }
    }

    /** Why a file can't be written, or null when it can; folders and denied paths aside. */
    fun readOnlyReason(path: Path): String? {
        val relative = relative(path)
        return when {
            denied(path) -> "WorldAPI doesn't share it"
            relative == "logs" || relative.startsWith("logs/") -> "Logs are read-only"
            relative in SERVER_OWNED -> "The server keeps it up to date by itself"
            path.name.substringAfterLast('.', "").lowercase() !in WRITABLE -> "Only config and text files can be written"
            else -> null
        }
    }

    fun requireFile(path: Path, access: Access): Path {
        if (!allows(path, access)) {
            val what = if (access == Access.READ) "read" else "written"
            val why = if (access == Access.WRITE) ": " + (readOnlyReason(path) ?: "").replaceFirstChar { it.lowercase() } else "; config, text, and log files only"
            forbidden("${relative(path)} can't be $what$why")
        }
        if (access == Access.READ && !path.isRegularFile()) notFound("file_not_found", "No file ${relative(path)}")
        return path
    }

    // ---- Reading ----

    /** Numbered lines; [nextOffset] continues where this read stopped, null at the end. */
    class Read(val lines: List<Pair<Int, String>>, val totalLines: Int, val cutLines: Int, val matches: Int?, val nextOffset: Int?, val sha256: String?)

    /**
     * Reads numbered lines, like an editor would show them:
     * - from line [offset] (1-based), [limit] lines;
     * - or the last [tail] lines;
     * - or with [grep], up to [limit] matching lines from [offset] on, each with [context] lines around it.
     * Lines longer than [maxLineChars] are cut, and the answer stops at about [maxBytes] of text.
     * A gzip file reads as its text. The whole file is scanned, so `totalLines` and `matches` count all of it.
     * A grep that runs past [grepNanos] stops with 400 `grep_timeout`.
     */
    fun read(path: Path, offset: Int, limit: Int, tail: Int?, grep: Regex?, context: Int, maxLineChars: Int, maxBytes: Int, grepNanos: Long = GREP_NANOS): Read {
        val gzip = path.name.lowercase().endsWith(".gz")
        if (!gzip) checkText(path)
        val digest = if (gzip) null else MessageDigest.getInstance("SHA-256")
        val out = ArrayList<Pair<Int, String>>()
        var total = 0
        var cut = 0
        var bytes = 0
        var matches = 0
        var shown = 0
        var next: Int? = null
        var after = 0
        val before = ArrayDeque<Pair<Int, String>>()
        val deadline = System.nanoTime() + grepNanos
        /** Adds a line unless the answer is full; the first line that doesn't fit becomes the next offset. */
        fun take(number: Int, text: String) {
            if (next != null) return
            if (out.isNotEmpty() && bytes + minOf(text.length, maxLineChars) > maxBytes) {
                next = number
                return
            }
            val line = if (text.length > maxLineChars) { cut++; text.take(maxLineChars) + " … [cut]" } else text
            out += number to line
            bytes += line.length + 8
        }
        open(path, gzip, digest).use { reader ->
            reader.lineSequence().forEach { text ->
                total++
                when {
                    tail != null -> {
                        before.addLast(total to text)
                        if (before.size > tail) before.removeFirst()
                    }
                    grep == null -> when {
                        total < offset -> {}
                        total < offset + limit -> take(total, text)
                        next == null -> next = total
                    }
                    total < offset -> {}
                    grep.containsMatchIn(Timed(text, deadline)) -> {
                        matches++
                        if (shown < limit) {
                            before.forEach { (n, t) -> take(n, t) }
                            take(total, text)
                            shown++
                            after = context
                        } else if (next == null) {
                            next = total
                        }
                        before.clear()
                    }
                    after > 0 -> {
                        take(total, text)
                        after--
                    }
                    context > 0 -> {
                        before.addLast(total to text)
                        if (before.size > context) before.removeFirst()
                    }
                }
            }
        }
        if (tail != null) before.forEach { (n, t) -> take(n, t) }
        return Read(out, total, cut, if (grep != null) matches else null, next, digest?.let { HexFormat.of().formatHex(it.digest()) })
    }

    private fun open(path: Path, gzip: Boolean, digest: MessageDigest?): BufferedReader {
        var input: InputStream = Files.newInputStream(path)
        if (digest != null) input = java.security.DigestInputStream(input, digest)
        if (gzip) input = GZIPInputStream(input)
        val decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)
        return BufferedReader(java.io.InputStreamReader(input, decoder), 64 * 1024)
    }

    /** A NUL byte in the first 8 KiB means binary; WorldAPI shares text only. */
    private fun checkText(path: Path) {
        val head = Files.newInputStream(path).use { it.readNBytes(8192) }
        if (head.any { it == 0.toByte() }) badRequest("${relative(path)} is binary", "binary_file")
    }

    fun text(path: Path): String {
        checkText(path)
        return Files.readString(path, StandardCharsets.UTF_8)
    }

    fun sha256(path: Path): String? = if (path.isRegularFile()) HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))) else null

    /**
     * A line that gives up once the deadline passes, so a regular expression that backtracks without end
     * (`(.*a){20}$` on a long line) can't hold a thread: the regex engine reads every character through [get].
     */
    private class Timed(private val text: CharSequence, private val deadline: Long) : CharSequence {
        private var reads = 0
        override val length get() = text.length
        override fun get(index: Int): Char {
            if (++reads and 0xFFF == 0 && System.nanoTime() > deadline) badRequest("grep took too long; use a simpler pattern", "grep_timeout")
            return text[index]
        }
        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = Timed(text.subSequence(startIndex, endIndex), deadline)
        override fun toString() = text.toString()
    }

    // ---- Writing ----

    class Written(val sha256: String, val size: Long, val backup: String?, val created: Boolean)

    /**
     * Replaces a file with [content] after checking [expectedSha256] against what is there now.
     * The previous version goes to the backups folder first, the last [keep] per file, and the new
     * one is written to a temporary file and moved into place, so a crash never leaves half a file.
     */
    @Synchronized
    fun write(path: Path, content: String, expectedSha256: String?, keep: Int): Written {
        val current = sha256(path)
        if (expectedSha256 != null && !expectedSha256.equals(current ?: "", ignoreCase = true)) {
            conflict("file_changed", "${relative(path)} changed since you read it (sha256 ${current ?: "none: the file doesn't exist"}); read it again")
        }
        val created = current == null
        if (created && !path.parent.isDirectory()) badRequest("The folder ${relative(path.parent)} doesn't exist", "folder_not_found")
        val backup = if (created) null else backup(path, keep)
        val bytes = content.toByteArray(StandardCharsets.UTF_8)
        val temporary = path.resolveSibling(".${path.name}.worldapi.tmp")
        Files.write(temporary, bytes)
        try {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
        }
        return Written(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), bytes.size.toLong(), backup?.let { backups.parent.relativize(it).toString().replace('\\', '/') }, created)
    }

    /** Keeps the current version in the backups folder, for a file the server rewrites itself. */
    @Synchronized
    fun keepBackup(path: Path, keep: Int): String? =
        if (path.isRegularFile()) backups.parent.relativize(backup(path, keep)).toString().replace('\\', '/') else null

    private fun backup(path: Path, keep: Int): Path {
        val folder = backups.resolve(relative(path))
        Files.createDirectories(folder)
        val target = folder.resolve(STAMP.format(Instant.now()) + "." + path.extension)
        Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING)
        Files.list(folder).use { stream -> stream.toList() }.sortedByDescending { it.name }.drop(keep).forEach { Files.deleteIfExists(it) }
        return target
    }

    // ---- Listing ----

    class Entry(val name: String, val path: String, val directory: Boolean, val size: Long?, val modified: String, val readable: Boolean, val writable: Boolean, val readOnlyReason: String?)

    fun list(folder: Path, max: Int): Pair<List<Entry>, Boolean> {
        if (!folder.isDirectory()) notFound("folder_not_found", "No folder ${relative(folder)}")
        val all = try {
            Files.list(folder).use { it.toList() }
        } catch (_: NoSuchFileException) {
            emptyList()
        }
        val visible = all.filterNot(::denied).sortedWith(compareBy<Path>({ !it.isDirectory() }, { it.name.lowercase() }))
        return visible.take(max).map {
            val directory = it.isDirectory()
            Entry(
                it.name, relative(it), directory,
                if (directory) null else runCatching { it.fileSize() }.getOrNull(),
                runCatching { it.getLastModifiedTime().toInstant().toString() }.getOrDefault(""),
                directory || allows(it, Access.READ), !directory && allows(it, Access.WRITE),
                if (directory) null else readOnlyReason(it),
            )
        } to (visible.size > max)
    }

    companion object {
        private val READABLE = setOf("yml", "yaml", "json", "json5", "properties", "toml", "conf", "cfg", "ini", "txt", "log", "lang", "mcmeta", "md", "csv", "sk")
        private val WRITABLE = READABLE - setOf("log")

        /** Files the server writes by itself; ops, the whitelist, and bans instead go through [ServerLists]. */
        private val SERVER_OWNED = setOf("usercache.json", "version_history.json")

        private val SECRET_NAMES = setOf("tokens.yml", "credentials.json", "secrets.yml", "secrets.yaml")
        private val SECRET_EXTENSIONS = listOf(".key", ".pem", ".p12", ".jks", ".keystore", ".env", ".db", ".sqlite", ".mv.db", ".jar")

        /** How long one grep may search a file. */
        private const val GREP_NANOS = 5_000_000_000L

        private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC)

        private fun forbidden(message: String): Nothing = throw ApiException(HttpStatusCode.Forbidden, "file_denied", message)
    }
}
