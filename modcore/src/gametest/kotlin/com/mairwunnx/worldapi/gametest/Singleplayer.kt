package com.mairwunnx.worldapi.gametest

import com.mairwunnx.worldapi.modcore.Discovery
import com.mairwunnx.worldapi.modcore.Mod
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.storage.LevelResource
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files

/**
 * The checks a singleplayer world must pass on either loader, called by each loader's client test: WorldAPI runs as
 * `integrated`, listens on 127.0.0.1 only, tells local clients where it is, keeps the world's data in its save, and
 * lets go of all of it when the world closes. Each check returns its problems; an empty list passes.
 */
object Singleplayer {
    private val client = HttpClient.newHttpClient()

    /**
     * How a check waits for an answer: a loader's client test freezes the game while it runs, so it lets ticks pass
     * until the future is done. Every request needs the server thread.
     */
    var await: (java.util.concurrent.CompletableFuture<*>) -> Unit = { it.join() }

    class World(val port: Int, val url: String, val token: String)

    /** On the server thread: where WorldAPI runs and keeps its data. */
    fun onServer(server: MinecraftServer): List<String> {
        val runtime = Mod.runtime ?: return listOf("WorldAPI didn't start in a singleplayer world")
        val save = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize()
        return listOfNotNull(
            "WorldAPI listens on ${runtime.address?.first}, not 127.0.0.1".takeUnless { runtime.address?.first == "127.0.0.1" },
            "the world's database isn't in its save".takeUnless { Files.isRegularFile(save.resolve("worldapi/worldapi.db")) },
            "config.yml isn't shared in config/worldapi".takeUnless { Files.isRegularFile(Mod.loader.config.resolve("worldapi/config.yml")) },
        )
    }

    /** Off the server thread: the world as a local client finds it. */
    fun open(): Pair<World?, List<String>> {
        val files = Files.list(Discovery.folder).use { stream -> stream.filter { it.fileName.toString().endsWith(".json") }.toList() }
        val entry = files.singleOrNull()?.let { Json.parseToJsonElement(Files.readString(it)).jsonObject } ?: return null to listOf("no single discovery file in ${Discovery.folder}: $files")
        val url = entry["url"]!!.jsonPrimitive.content
        val token = entry["token"]!!.jsonPrimitive.content
        val port = URI.create(url).port
        val world = World(port, url, token)
        val server = get(world, "/server")
        val info = runCatching { Json.parseToJsonElement(server.body()).jsonObject }.getOrNull()
        val problems = listOfNotNull(
            "GET /server answered ${server.statusCode()}".takeUnless { server.statusCode() == 200 },
            "side isn't integrated".takeUnless { info?.get("side")?.jsonPrimitive?.content == "integrated" },
            "platform isn't ${Mod.loader.id}".takeUnless { info?.get("platform")?.jsonPrimitive?.content == Mod.loader.id },
            "lan isn't false".takeUnless { info?.get("lan")?.jsonPrimitive?.content == "false" },
            "a request to another host name got through".takeUnless { get(world, "/server", host = "evil.example:$port").statusCode() == 403 },
            "a page from another site got through".takeUnless { get(world, "/server", origin = "https://evil.example").statusCode() == 403 },
        )
        return world to problems
    }

    /** Leaves a mark in this world: a token only it knows. */
    fun mark(world: World, name: String): List<String> {
        val created = send(world, HttpRequest.newBuilder(URI.create(world.url + "/tokens")).POST(HttpRequest.BodyPublishers.ofString("""{"name":"$name","scopes":["viewer"]}""")))
        return listOfNotNull("POST /tokens answered ${created.statusCode()}: ${created.body()}".takeUnless { created.statusCode() == 201 || created.statusCode() == 200 })
    }

    /** Whether this world knows the mark. */
    fun marked(world: World, name: String): Boolean {
        val tokens = get(world, "/tokens")
        return Json.parseToJsonElement(tokens.body()).jsonArray.any { it.jsonObject["name"]?.jsonPrimitive?.content == name }
    }

    /** After the world closed: the port is free and the discovery file gone. */
    fun closed(world: World): List<String> = listOfNotNull(
        "port ${world.port} is still taken".takeUnless { runCatching { ServerSocket().use { it.bind(InetSocketAddress("127.0.0.1", world.port)) } }.isSuccess },
        "the discovery file outlived the world".takeIf { Files.exists(Discovery.folder.resolve("${world.port}.json")) },
    )

    private fun get(world: World, path: String, host: String? = null, origin: String? = null): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create(world.url + path)).GET()
        // The JDK's client won't set Host itself; a raw socket can, and so does a browser fooled by DNS rebinding.
        if (host != null) return raw(world, path, host)
        origin?.let { request.header("Origin", it) }
        return send(world, request)
    }

    private fun send(world: World, request: HttpRequest.Builder): HttpResponse<String> {
        val answer = client.sendAsync(request.header("Authorization", "Bearer ${world.token}").header("Content-Type", "application/json").build(), HttpResponse.BodyHandlers.ofString())
        await(answer)
        return answer.join()
    }

    /** A request with a Host header of our choosing, over a plain socket. */
    private fun raw(world: World, path: String, host: String): HttpResponse<String> {
        val target = URI.create(world.url + path)
        val read = java.util.concurrent.CompletableFuture.supplyAsync {
            java.net.Socket("127.0.0.1", world.port).use { socket ->
                socket.getOutputStream().write("GET ${target.rawPath} HTTP/1.1\r\nHost: $host\r\nAuthorization: Bearer ${world.token}\r\nConnection: close\r\n\r\n".toByteArray())
                socket.getInputStream().readBytes().decodeToString()
            }
        }
        await(read)
        run {
            val response = read.join()
            val status = response.substringAfter(' ').substringBefore(' ').toIntOrNull() ?: 0
            return object : HttpResponse<String> {
                override fun statusCode() = status
                override fun body() = response.substringAfter("\r\n\r\n")
                override fun request() = HttpRequest.newBuilder(target).build()
                override fun previousResponse() = java.util.Optional.empty<HttpResponse<String>>()
                override fun headers() = java.net.http.HttpHeaders.of(emptyMap()) { _, _ -> true }
                override fun sslSession() = java.util.Optional.empty<javax.net.ssl.SSLSession>()
                override fun uri() = target
                override fun version() = HttpClient.Version.HTTP_1_1
            }
        }
    }
}
