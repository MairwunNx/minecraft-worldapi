package com.mairwunnx.worldapi.admin

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Logger
import java.util.zip.ZipFile
import javax.imageio.ImageIO

/**
 * Item icons for the admin panel. Mojang's textures may not ship inside a public jar, so WorldAPI
 * downloads the game's client for the server's version from Mojang, once, into its cache folder,
 * and reads icons out of it: an item's model, its parents, then the texture, cropped to the first
 * frame. Block items show one face, flat. Everything stays on this server.
 */
class Icons(private val folder: Path, private val version: () -> String, private val logger: Logger) {
    enum class State { IDLE, LOADING, READY, FAILED }

    private val state = AtomicReference(State.IDLE)
    @Volatile private var jar: ZipFile? = null
    private val cache = ConcurrentHashMap<String, ByteArray>()
    private val missing = ConcurrentHashMap.newKeySet<String>()

    val status: State get() = state.get()

    /** The PNG for an item key such as `minecraft:diamond_sword`, or null when it has none. Starts the download the first time. */
    fun icon(key: String): ByteArray? {
        val zip = jar ?: run { start(); return null }
        val id = key.removePrefix("minecraft:").lowercase()
        if (!Regex("[a-z0-9_./-]+").matches(id) || id in missing) return null
        cache[id]?.let { return it }
        val icon = runCatching { render(zip, id) }.getOrNull()
        if (icon == null) missing += id else cache[id] = icon
        return icon
    }

    /** Downloads the client on a background thread unless it's there or on its way. */
    fun start() {
        if (!state.compareAndSet(State.IDLE, State.LOADING) && !state.compareAndSet(State.FAILED, State.LOADING)) return
        Thread({
            try {
                jar = ZipFile(download().toFile())
                state.set(State.READY)
            } catch (error: Exception) {
                logger.warning("Couldn't get the game's textures for item icons: ${error.message}")
                state.set(State.FAILED)
            }
        }, "WorldAPI-icons").apply { isDaemon = true }.start()
    }

    fun close() = jar?.close()

    private fun download(): Path {
        val id = version()
        val file = folder.resolve("client-$id.jar")
        if (Files.isRegularFile(file)) return file
        Files.createDirectories(folder)
        val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
        fun get(url: String): ByteArray = http.send(HttpRequest.newBuilder(URI(url)).build(), HttpResponse.BodyHandlers.ofByteArray())
            .also { require(it.statusCode() == 200) { "$url answered ${it.statusCode()}" } }.body()
        val manifest = Json.parseToJsonElement(get(MANIFEST).decodeToString()).jsonObject
        val entry = manifest["versions"]!!.jsonArray.map { it.jsonObject }.firstOrNull { it["id"]?.jsonPrimitive?.content == id }
            ?: error("Mojang lists no version $id")
        val meta = Json.parseToJsonElement(get(entry["url"]!!.jsonPrimitive.content).decodeToString()).jsonObject
        val client = meta["downloads"]!!.jsonObject["client"]!!.jsonObject
        logger.info("Downloading the $id client from Mojang once, for item icons in the admin panel")
        val bytes = get(client["url"]!!.jsonPrimitive.content)
        val sha1 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes))
        require(sha1 == client["sha1"]?.jsonPrimitive?.content) { "the client's SHA-1 doesn't match Mojang's" }
        val temporary = folder.resolve("client-$id.jar.tmp")
        Files.write(temporary, bytes)
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
        return file
    }

    private fun render(zip: ZipFile, id: String): ByteArray? {
        val texture = texture(zip, id) ?: return null
        val path = "assets/minecraft/textures/" + texture.removePrefix("minecraft:") + ".png"
        val image = zip.getEntry(path)?.let { entry -> zip.getInputStream(entry).use(ImageIO::read) } ?: return null
        // Animated textures stack their frames vertically: the first square is the icon.
        val square = if (image.height > image.width) image.getSubimage(0, 0, image.width, image.width) else image
        return ByteArrayOutputStream().also { ImageIO.write(square, "png", it) }.toByteArray()
    }

    /** The texture an item shows: its item definition's model, then up the model's parents. */
    private fun texture(zip: ZipFile, id: String): String? {
        val definition = json(zip, "assets/minecraft/items/$id.json")
        var model = definition?.let(::firstModel) ?: "minecraft:item/$id"
        repeat(8) {
            val node = json(zip, "assets/minecraft/models/" + model.removePrefix("minecraft:") + ".json") ?: return fallback(zip, id)
            val textures = node["textures"] as? JsonObject
            if (textures != null) {
                fun resolve(key: String, depth: Int = 0): String? {
                    val value = (textures[key] as? JsonPrimitive)?.contentOrNull ?: return null
                    return if (value.startsWith("#") && depth < 4) resolve(value.removePrefix("#"), depth + 1) else value
                }
                PREFERRED.firstNotNullOfOrNull { resolve(it) }?.let { return it }
            }
            model = (node["parent"] as? JsonPrimitive)?.contentOrNull?.takeUnless { it.startsWith("builtin/") } ?: return fallback(zip, id)
        }
        return fallback(zip, id)
    }

    private fun fallback(zip: ZipFile, id: String) = listOf("item/$id", "block/$id").firstOrNull { zip.getEntry("assets/minecraft/textures/$it.png") != null }

    /** The first plain model an item definition names, through selects, conditions, and ranges. */
    private fun firstModel(node: JsonElement): String? = when (node) {
        is JsonObject -> (node["model"] as? JsonPrimitive)?.contentOrNull
            ?: node.values.firstNotNullOfOrNull { firstModel(it) }
        is JsonArray -> node.firstNotNullOfOrNull { firstModel(it) }
        else -> null
    }

    private fun json(zip: ZipFile, path: String): JsonObject? =
        zip.getEntry(path)?.let { entry -> zip.getInputStream(entry).use { Json.parseToJsonElement(it.readBytes().decodeToString()) as? JsonObject } }

    companion object {
        private const val MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
        private val PREFERRED = listOf("layer0", "all", "side", "front", "top", "texture", "cross", "plant", "end", "wool", "pattern", "particle")
    }
}
