package com.mairwunnx.worldapi.modcore

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.util.logging.Logger

/**
 * Where a singleplayer world's WorldAPI tells local clients how to reach it: `~/.worldapi/local/<port>.json`, for as
 * long as the world is open. minecraft-mcp reads the folder when it has no `WORLDAPI_URL`, so it finds the game
 * whatever the launcher, the folder, or the port. The file holds the token, so only its owner may read it, as with
 * config.yml; a client skips a file whose process is gone.
 */
object Discovery {
    @Serializable
    data class Entry(
        val url: String,
        val token: String,
        /** The save's name, as the world list shows it. */
        val world: String,
        val platform: String,
        val version: String,
        /** The game's process, so a client can tell a file left by a crash. */
        val pid: Long,
        val startedAt: String,
    )

    private val json = Json { prettyPrint = true }

    /** `~/.worldapi/local`; tests point `worldapi.discovery` elsewhere, so they leave the real one alone. */
    val folder: Path get() = System.getProperty("worldapi.discovery")?.let(Path::of) ?: Path.of(System.getProperty("user.home"), ".worldapi", "local")

    /** Writes the entry; the returned function removes it again. */
    fun publish(entry: Entry, port: Int, logger: Logger): () -> Unit {
        val file = folder.resolve("$port.json")
        try {
            Files.createDirectories(folder)
            val temp = Files.createTempFile(folder, ".$port", ".tmp")
            owned(temp)
            Files.writeString(temp, json.encodeToString(Entry.serializer(), entry))
            Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        } catch (error: Exception) {
            logger.warning("Couldn't write $file for local clients: ${error.message}")
        }
        return { runCatching { Files.deleteIfExists(file) } }
    }

    fun entry(url: String, token: String, world: String, platform: String, version: String) =
        Entry(url, token, world, platform, version, ProcessHandle.current().pid(), Instant.now().toString())

    /** Only the user may read it, where the file system knows permissions. */
    private fun owned(file: Path) {
        runCatching { Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------")) }
    }
}
