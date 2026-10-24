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
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.geom.Point2D
import java.awt.image.BufferedImage

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
        // Clients pick the keys, so the list of misses stays bounded.
        if (icon == null) { if (missing.size < 10_000) missing += id } else cache[id] = icon
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

    /** A model with its parents merged: the textures by key, and the parents it went through. */
    private class Model(val textures: Map<String, String>, val parents: List<String>) {
        fun texture(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
            var value = textures[key]
            repeat(4) { if (value?.startsWith("#") == true) value = textures[value!!.removePrefix("#")] }
            value?.takeUnless { it.startsWith("#") }
        }
    }

    private enum class Shape { FLAT, CUBE, SLAB }

    private fun render(zip: ZipFile, id: String): ByteArray? {
        chest(zip, id)?.let { return png(it) }
        val model = model(zip, id)
        val keys = model?.textures?.keys.orEmpty()
        val shape = when {
            model == null || "layer0" in keys -> Shape.FLAT
            model.parents.any { it.endsWith("block/slab") } -> Shape.SLAB
            model.parents.any { parent -> CUBES.any { parent.endsWith(it) } } -> Shape.CUBE
            // Blocks with their own elements, like grass: a top and a side make a cube.
            keys.any { it in setOf("top", "up", "end") } && keys.any { it in setOf("side", "north") } -> Shape.CUBE
            else -> Shape.FLAT
        }
        if (shape == Shape.FLAT) {
            val texture = model?.texture(*PREFERRED.toTypedArray()) ?: fallback(zip, id) ?: return null
            return png(image(zip, texture) ?: return null)
        }
        val top = image(zip, model!!.texture("up", "top", "end", "all", "side", "texture", "particle") ?: return null) ?: return null
        val left = image(zip, model.texture("north", "front", "side", "all", "texture", "particle") ?: return null) ?: return null
        val right = image(zip, model.texture("east", "side", "all", "texture", "particle") ?: return null) ?: return null
        val tint = tint(model.texture("up", "top", "all", "side", "texture").orEmpty())
        return png(cube(tint?.let { tinted(top, it) } ?: top, tint?.let { tinted(left, it) } ?: left, tint?.let { tinted(right, it) } ?: right, shape == Shape.SLAB))
    }

    /**
     * Chests draw with the game's own code from a 64×64 entity texture, not a block model: the lid
     * and the base each have their top, sides, and front laid out by the box's size, 14 wide and 14
     * deep, the lid 5 tall and the base 10. The latch sits on the front.
     */
    private fun chest(zip: ZipFile, id: String): BufferedImage? {
        val definition = json(zip, "assets/minecraft/items/$id.json") ?: return null
        fun find(node: JsonElement): String? = when (node) {
            is JsonObject -> if ((node["type"] as? JsonPrimitive)?.contentOrNull == "minecraft:chest") {
                (node["texture"] as? JsonPrimitive)?.contentOrNull?.takeUnless { it.endsWith("christmas") }
            } else {
                node.values.firstNotNullOfOrNull(::find)
            }
            is JsonArray -> node.firstNotNullOfOrNull(::find)
            else -> null
        }
        val name = find(definition) ?: return null
        val atlas = image(zip, "entity/chest/" + name.removePrefix("minecraft:")) ?: return null
        val unit = atlas.width / 64
        fun part(x: Int, y: Int, w: Int, h: Int) = atlas.getSubimage(x * unit, y * unit, w * unit, h * unit)
        fun stack(lid: BufferedImage, base: BufferedImage): BufferedImage {
            val out = BufferedImage(lid.width, lid.height + base.height, BufferedImage.TYPE_INT_ARGB)
            out.createGraphics().apply { drawImage(lid, 0, 0, null); drawImage(base, 0, lid.height, null); dispose() }
            return out
        }
        val front = stack(part(14, 14, 14, 5), part(14, 33, 14, 10))
        front.createGraphics().apply { drawImage(part(1, 1, 2, 4), 6 * unit, 2 * unit, null); dispose() }
        val side = stack(part(28, 14, 14, 5), part(28, 33, 14, 10))
        return cube(part(28, 0, 14, 14), front, side, slab = false, height = 15.0 / 16)
    }

    /** The model an item shows, its item definition first, merged with every parent. */
    private fun model(zip: ZipFile, id: String): Model? {
        val definition = json(zip, "assets/minecraft/items/$id.json")
        var name: String? = definition?.let(::firstModel) ?: "minecraft:item/$id"
        val textures = mutableMapOf<String, String>()
        val parents = mutableListOf<String>()
        var found = false
        repeat(10) {
            val current = name ?: return@repeat
            parents += current.removePrefix("minecraft:")
            val node = json(zip, "assets/minecraft/models/" + current.removePrefix("minecraft:") + ".json") ?: return@repeat
            found = true
            // A texture is a name, or since 26.x an object with its sprite and how to draw it.
            (node["textures"] as? JsonObject)?.forEach { (key, value) ->
                val sprite = (value as? JsonPrimitive)?.contentOrNull ?: ((value as? JsonObject)?.get("sprite") as? JsonPrimitive)?.contentOrNull
                sprite?.let { textures.putIfAbsent(key, it) }
            }
            name = (node["parent"] as? JsonPrimitive)?.contentOrNull?.takeUnless { it.startsWith("builtin/") }
        }
        return if (found) Model(textures, parents) else null
    }

    private fun image(zip: ZipFile, texture: String): BufferedImage? {
        val path = "assets/minecraft/textures/" + texture.removePrefix("minecraft:") + ".png"
        val image = zip.getEntry(path)?.let { entry -> zip.getInputStream(entry).use(ImageIO::read) } ?: return null
        // Animated textures stack their frames vertically: the first square is the icon.
        val square = if (image.height > image.width) image.getSubimage(0, 0, image.width, image.width) else image
        return BufferedImage(square.width, square.height, BufferedImage.TYPE_INT_ARGB).also { it.createGraphics().apply { drawImage(square, 0, 0, null); dispose() } }
    }

    private fun png(image: BufferedImage) = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()

    /**
     * Draws a block the way the game's inventory does: an isometric cube, the top lit fully, the
     * left face at 80 %, the right at 60 %. A slab is half as tall.
     */
    private fun cube(top: BufferedImage, left: BufferedImage, right: BufferedImage, slab: Boolean, height: Double = 1.0): BufferedImage {
        val size = ICON
        val half = 27.0                      // half the cube's width on screen
        val rise = half * Math.tan(Math.toRadians(30.0))
        val edge = half / Math.cos(Math.toRadians(30.0)) * (if (slab) 0.5 else 1.0) * height
        val cx = size / 2.0
        val full = half / Math.cos(Math.toRadians(30.0))
        val y0 = (size - (2 * rise + full)) / 2 + (full - edge)
        val t = Point2D.Double(cx, y0)
        val l = Point2D.Double(cx - half, y0 + rise)
        val r = Point2D.Double(cx + half, y0 + rise)
        val c = Point2D.Double(cx, y0 + 2 * rise)
        val down = Point2D.Double(0.0, edge)
        val out = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)
        // A slab's sides show the texture's lower half, as the game cuts them.
        val leftFace = if (slab) left.getSubimage(0, left.height / 2, left.width, left.height / 2) else left
        val rightFace = if (slab) right.getSubimage(0, right.height / 2, right.width, right.height / 2) else right
        face(g, shade(leftFace, 0.8f), l, c, down)
        face(g, shade(rightFace, 0.6f), c, r, down)
        face(g, top, t, r, Point2D.Double(l.x - t.x, l.y - t.y))
        g.dispose()
        return out
    }

    /** Maps a texture onto the parallelogram from [origin] along [u] (to [end]) and [v], a hair larger to close the seams. */
    private fun face(g: java.awt.Graphics2D, texture: BufferedImage, origin: Point2D.Double, end: Point2D.Double, v: Point2D.Double) {
        val ux = (end.x - origin.x) / texture.width
        val uy = (end.y - origin.y) / texture.width
        val vx = v.x / texture.height
        val vy = v.y / texture.height
        val grow = 1.02
        g.drawImage(texture, AffineTransform(ux * grow, uy * grow, vx * grow, vy * grow, origin.x, origin.y), null)
    }

    private fun shade(image: BufferedImage, light: Float) = tinted(image, 0xFFFFFF, light)

    /** Multiplies each pixel by [rgb] and [light], as the game tints grass and leaves and shades faces. */
    private fun tinted(image: BufferedImage, rgb: Int, light: Float = 1f): BufferedImage {
        val out = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        val tr = (rgb shr 16 and 255) / 255f * light
        val tg = (rgb shr 8 and 255) / 255f * light
        val tb = (rgb and 255) / 255f * light
        for (x in 0 until image.width) for (y in 0 until image.height) {
            val p = image.getRGB(x, y)
            val red = ((p shr 16 and 255) * tr).toInt()
            val green = ((p shr 8 and 255) * tg).toInt()
            val blue = ((p and 255) * tb).toInt()
            out.setRGB(x, y, (p and 0xFF000000.toInt()) or (red shl 16) or (green shl 8) or blue)
        }
        return out
    }

    /** The game colors some textures by biome; the inventory uses the plains colors. */
    private fun tint(texture: String): Int? {
        val name = texture.substringAfterLast('/')
        return when {
            name == "grass_block_top" || name == "short_grass" || name == "fern" -> 0x91BD59
            name == "spruce_leaves" -> 0x619961
            name == "birch_leaves" -> 0x80A755
            name.endsWith("_leaves") && name !in setOf("cherry_leaves", "azalea_leaves", "flowering_azalea_leaves", "pale_oak_leaves") -> 0x48B518
            name == "vine" || name == "lily_pad" -> 0x48B518
            else -> null
        }
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
        /** Pixels on a side of a block's icon; flat items keep their texture's size. */
        private const val ICON = 64

        /** Parent models that make a full cube. */
        private val CUBES = listOf("block/cube", "block/cube_all", "block/cube_column", "block/cube_column_horizontal", "block/cube_bottom_top",
            "block/cube_top", "block/orientable", "block/orientable_with_bottom", "block/cube_directional", "block/leaves", "block/cube_mirrored_all",
            "block/stairs", "block/template_glazed_terracotta")

        private val PREFERRED = listOf("layer0", "all", "side", "front", "top", "texture", "cross", "plant", "end", "wool", "pattern", "particle")
    }
}
