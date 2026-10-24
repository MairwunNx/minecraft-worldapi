package com.mairwunnx.worldapi

import com.mairwunnx.worldapi.api.ApiException
import com.mairwunnx.worldapi.files.ConfigValues
import com.mairwunnx.worldapi.files.ServerFiles
import com.mairwunnx.worldapi.files.ServerFiles.Access
import com.mairwunnx.worldapi.routes.lineDiff
import com.mairwunnx.worldapi.routes.numbered
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FilesTest {
    private val root: Path = Files.createTempDirectory("server")
    private val own = root.resolve("plugins/WorldAPI").createDirectories()
    private val files = ServerFiles(root, own, own.resolve("backups"))

    private fun status(block: () -> Unit) = assertFailsWith<ApiException> { block() }.status.value

    @Test
    fun `paths stay inside the server folder`() {
        assertEquals(400, status { files.resolve("../etc/passwd", Access.READ) })
        assertEquals(400, status { files.resolve("plugins/../../x.yml", Access.READ) })
        assertEquals(root.resolve("server.properties"), files.resolve("/server.properties", Access.READ))
        assertEquals(root.resolve("config/paper-global.yml"), files.resolve("config\\paper-global.yml", Access.READ))
    }

    @Test
    fun `symlinks out of the server folder are refused`() {
        val outside = Files.createTempDirectory("outside").resolve("secret.yml").apply { writeText("a: 1") }
        Files.createSymbolicLink(root.resolve("link.yml"), outside)
        Files.createSymbolicLink(root.resolve("dir"), outside.parent)
        assertEquals(403, status { files.resolve("link.yml", Access.READ) })
        assertEquals(403, status { files.resolve("dir/secret.yml", Access.READ) })
    }

    @Test
    fun `WorldAPI's folder, hidden files, and secrets are never shared`() {
        own.resolve("config.yml").writeText("tokens: []")
        assertEquals(403, status { files.resolve("plugins/WorldAPI/config.yml", Access.READ) })
        assertEquals(403, status { files.resolve("plugins/WorldAPI", Access.READ) })
        assertEquals(403, status { files.resolve(".env", Access.READ) })
        assertEquals(403, status { files.resolve("plugins/Plan/.secret/x.yml", Access.READ) })
        assertEquals(403, status { files.resolve("plugins/Some/tokens.yml", Access.READ) })
        assertEquals(403, status { files.resolve("plugins/Some/data.db", Access.READ) })
        val (entries, _) = files.list(root.resolve("plugins"), 100)
        assertTrue(entries.none { it.name == "WorldAPI" })
    }

    @Test
    fun `logs and server-owned files can be read but not written`() {
        root.resolve("logs").createDirectories()
        root.resolve("logs/latest.log").writeText("hello")
        root.resolve("usercache.json").writeText("[]")
        assertTrue(files.allows(root.resolve("logs/latest.log"), Access.READ))
        assertFalse(files.allows(root.resolve("logs/latest.log"), Access.WRITE))
        assertTrue(files.allows(root.resolve("usercache.json"), Access.READ))
        assertFalse(files.allows(root.resolve("usercache.json"), Access.WRITE))
        // The server applies these itself, so they're writable.
        assertTrue(files.allows(root.resolve("ops.json"), Access.WRITE))
        assertEquals("Logs are read-only", files.readOnlyReason(root.resolve("logs/latest.log")))
        assertTrue(files.allows(root.resolve("bukkit.yml"), Access.WRITE))
        assertFalse(files.allows(root.resolve("paper.jar"), Access.READ))
        assertFalse(files.allows(root.resolve("world/region/r.0.0.mca"), Access.READ))
        assertEquals(403, status { files.requireFile(root.resolve("usercache.json"), Access.WRITE) })
    }

    @Test
    fun `reads page through numbered lines`() {
        val file = root.resolve("big.txt").apply { writeText((1..10).joinToString("\n") { "line $it" }) }
        val first = files.read(file, offset = 1, limit = 3, tail = null, grep = null, context = 0, maxLineChars = 2000, maxBytes = 10_000)
        assertEquals(listOf(1 to "line 1", 2 to "line 2", 3 to "line 3"), first.lines)
        assertEquals(10, first.totalLines)
        assertEquals(4, first.nextOffset)
        val last = files.read(file, offset = 9, limit = 5, tail = null, grep = null, context = 0, maxLineChars = 2000, maxBytes = 10_000)
        assertEquals(listOf(9, 10), last.lines.map { it.first })
        assertNull(last.nextOffset)
        val tail = files.read(file, offset = 1, limit = 200, tail = 2, grep = null, context = 0, maxLineChars = 2000, maxBytes = 10_000)
        assertEquals(listOf(9, 10), tail.lines.map { it.first })
        assertEquals(64, first.sha256!!.length)
    }

    @Test
    fun `grep shows matches with context and pages on`() {
        val file = root.resolve("log.txt").apply { writeText("a\nERROR one\nb\nc\nd\nERROR two\ne\nERROR three") }
        val read = files.read(file, 1, limit = 2, tail = null, grep = Regex("error", RegexOption.IGNORE_CASE), context = 1, maxLineChars = 2000, maxBytes = 10_000)
        assertEquals(listOf(1, 2, 3, 5, 6, 7), read.lines.map { it.first })
        assertEquals(3, read.matches)
        assertEquals(8, read.nextOffset)
        assertEquals("1\ta\n2\tERROR one\n3\tb\n--\n5\td\n6\tERROR two\n7\te\n", numbered(read.lines))
    }

    @Test
    fun `a grep that backtracks forever gives up`() {
        val file = root.resolve("evil.txt").apply { writeText("a".repeat(40) + "!") }
        val started = System.nanoTime()
        val code = status { files.read(file, 1, 10, null, Regex("(.*a){20}$"), 0, 2000, 10_000, grepNanos = 200_000_000L) }
        assertEquals(400, code)
        assertTrue(System.nanoTime() - started < 5_000_000_000L)
    }

    @Test
    fun `long lines are cut and answers stop at the size limit`() {
        val file = root.resolve("wide.txt").apply { writeText("x".repeat(50) + "\nshort\n" + "y".repeat(30)) }
        val read = files.read(file, 1, 200, null, null, 0, maxLineChars = 10, maxBytes = 40)
        assertEquals("xxxxxxxxxx … [cut]", read.lines.first().second)
        assertEquals(1, read.cutLines)
        assertEquals(3, read.nextOffset)
    }

    @Test
    fun `gzip logs read as text and binaries are refused`() {
        val gz = root.resolve("logs").createDirectories().resolve("2026-10-19-1.log.gz")
        GZIPOutputStream(Files.newOutputStream(gz)).use { it.write("first\nsecond\n".toByteArray()) }
        val read = files.read(gz, 1, 10, null, null, 0, 2000, 10_000)
        assertEquals(listOf(1 to "first", 2 to "second"), read.lines)
        assertNull(read.sha256)
        val binary = root.resolve("blob.txt").apply { Files.write(this, byteArrayOf(1, 0, 2)) }
        assertEquals(400, status { files.read(binary, 1, 10, null, null, 0, 2000, 10_000) })
    }

    @Test
    fun `writes check the hash, keep backups, and prune them`() {
        val file = root.resolve("bukkit.yml").apply { writeText("a: 1\n") }
        val hash = files.sha256(file)!!
        assertEquals(409, status { files.write(file, "a: 2\n", "0".repeat(64), keep = 2) })
        val written = files.write(file, "a: 2\n", hash, keep = 2)
        assertEquals("a: 2\n", file.readText())
        assertContains(written.backup!!, "backups/bukkit.yml/")
        repeat(3) { files.write(file, "a: ${it + 3}\n", null, keep = 2); Thread.sleep(2) }
        assertEquals(2, Files.list(own.resolve("backups/bukkit.yml")).use { it.count() })
        val created = files.write(root.resolve("new.yml"), "b: 1\n", null, keep = 2)
        assertTrue(created.created)
        assertEquals(400, status { files.write(root.resolve("missing/new.yml"), "", null, keep = 2) })
    }

    @Test
    fun `edits replace text that appears once`() {
        val text = "a: 1\nb: 1\n"
        assertEquals("a: 2\nb: 1\n", ConfigValues.edit(text, listOf(ConfigValues.Edit("a: 1", "a: 2", false))))
        assertEquals(400, status { ConfigValues.edit(text, listOf(ConfigValues.Edit(": 1", ": 2", false))) })
        assertEquals("a: 2\nb: 2\n", ConfigValues.edit(text, listOf(ConfigValues.Edit(": 1", ": 2", true))))
        assertEquals(400, status { ConfigValues.edit(text, listOf(ConfigValues.Edit("c: 1", "c: 2", false))) })
        // All or nothing: the second edit fails, so the first doesn't count either.
        assertEquals(400, status { ConfigValues.edit(text, listOf(ConfigValues.Edit("a: 1", "a: 2", false), ConfigValues.Edit("zzz", "y", false))) })
    }

    @Test
    fun `edits keep CRLF line endings`() {
        assertEquals("a: 2\r\nb: 1\r\n", ConfigValues.edit("a: 1\r\nb: 1\r\n", listOf(ConfigValues.Edit("a: 1\nb", "a: 2\nb", false))))
    }

    @Test
    fun `YAML values change and comments stay`() {
        val text = "# Paper settings\nchunks:\n  # How often to save\n  auto-save-interval: default\n  max: 8\n"
        val result = ConfigValues.set("paper-world-defaults.yml", text, mapOf("chunks.auto-save-interval" to JsonPrimitive(6000), "chunks.max" to JsonNull, "new.key" to JsonPrimitive(true)))
        assertContains(result, "# Paper settings")
        assertContains(result, "# How often to save")
        assertContains(result, "auto-save-interval: 6000")
        assertFalse("max:" in result)
        assertContains(result, "new:\n  key: true")
    }

    @Test
    fun `properties change in place`() {
        val text = "#Minecraft server properties\nmotd=Old\nview-distance=10\n"
        val result = ConfigValues.set("server.properties", text, mapOf("motd" to JsonPrimitive("New"), "pvp" to JsonPrimitive(false), "view-distance" to JsonNull))
        assertEquals("#Minecraft server properties\nmotd=New\npvp=false\n", result)
    }

    @Test
    fun `JSON values set by path`() {
        val result = ConfigValues.set("settings.json", "{\"a\": {\"b\": 1}, \"c\": 2}", mapOf("a.b" to JsonPrimitive(5), "a.d" to JsonPrimitive("x"), "c" to JsonNull))
        assertEquals("{\n  \"a\": {\n    \"b\": 5,\n    \"d\": \"x\"\n  }\n}\n", result)
        assertEquals(400, status { ConfigValues.set("config.toml", "", mapOf("a" to JsonPrimitive(1))) })
    }

    @Test
    fun `line diff counts added and removed lines`() {
        assertEquals(1 to 1, lineDiff("a\nb\nc", "a\nB\nc"))
        assertEquals(2 to 0, lineDiff("a", "a\nb\nc"))
    }
}
