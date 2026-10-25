package com.mairwunnx.worldapi.gametest

import com.mairwunnx.worldapi.modcore.Mod
import com.mojang.authlib.GameProfile
import io.netty.channel.embedded.EmbeddedChannel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.network.Connection
import net.minecraft.network.protocol.PacketFlow
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.network.CommonListenerCookie
import net.minecraft.world.level.GameType
import net.minecraft.world.phys.Vec3
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * WorldAPI as a client sees it during a game test: real HTTP to the server's own port, with config.yml's token. A test
 * runs on the server thread, which every handler needs, so a call never blocks: it answers a future the test waits
 * for with [GameTestHelper.succeedWhen].
 */
object Api {
    private val client = HttpClient.newHttpClient()

    class Answer(val status: Int, val body: String) {
        val json: JsonElement by lazy { Json.parseToJsonElement(body) }
    }

    private val runtime get() = Mod.runtime ?: error("WorldAPI didn't start on the test server")

    val base: String get() = runtime.address!!.let { (host, port) -> "http://${if (host == "0.0.0.0") "127.0.0.1" else host}:$port${runtime.config.get().http.basePath}" }

    private val token: String get() = runtime.config.get().auth.tokens.first().token

    fun call(method: String, path: String, body: String? = null, headers: Map<String, String> = emptyMap(), auth: Boolean = true): CompletableFuture<Answer> {
        val request = HttpRequest.newBuilder(URI.create(base + path)).method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody())
            .header("Content-Type", "application/json")
        if (auth) request.header("Authorization", "Bearer $token")
        headers.forEach { (name, value) -> request.header(name, value) }
        return client.sendAsync(request.build(), HttpResponse.BodyHandlers.ofString()).thenApply { Answer(it.statusCode(), it.body()) }
    }

    fun get(path: String) = call("GET", path)

    /** Waits for [answer] and checks it, failing the test with what came back. */
    fun GameTestHelper.expect(answer: CompletableFuture<Answer>, check: (Answer) -> String?) {
        succeedWhen {
            assertTrue(answer.isDone, "WorldAPI hasn't answered yet")
            val result = runCatching { answer.join() }.getOrElse { error -> throw AssertionError("The call failed: $error", error) }
            check(result)?.let { fail("$it; WorldAPI answered ${result.status}: ${result.body.take(400)}") }
        }
    }

    /** A survival player on an embedded connection, who joins as a client would, so every join hook runs. */
    fun player(helper: GameTestHelper, at: Vec3 = Vec3(1.5, 2.0, 1.5)): ServerPlayer {
        val server = helper.level.server
        val cookie = CommonListenerCookie.createInitial(UUID.randomUUID().let { GameProfile(it, "wa-${it.toString().take(8)}") }, false)
        val player = ServerPlayer(server, helper.level, cookie.gameProfile(), cookie.clientInformation())
        val connection = Connection(PacketFlow.SERVERBOUND)
        EmbeddedChannel(connection)
        server.playerList.placeNewPlayer(connection, player, cookie)
        player.setGameMode(GameType.SURVIVAL)
        player.connection.handleAcceptPlayerLoad(ServerboundPlayerLoadedPacket())
        val world = helper.absoluteVec(at)
        player.teleportTo(world.x, world.y, world.z)
        return player
    }
}
