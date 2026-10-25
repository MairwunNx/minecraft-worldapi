package com.mairwunnx.worldapi.fabric.gametest

import com.mairwunnx.worldapi.gametest.Singleplayer
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import java.nio.file.Files

/**
 * A real client in two singleplayer worlds, one after the other ([Singleplayer] has the checks): WorldAPI answers on
 * 127.0.0.1 in the first, frees its port when the world closes, and the second world has data of its own.
 * `./gradlew :fabric:runClientGameTest` opens a game window.
 */
class SingleplayerClientTest : FabricClientGameTest {
    override fun runTest(context: ClientGameTestContext) {
        System.setProperty("worldapi.discovery", Files.createTempDirectory("worldapi-local").toString())
        val mark = "first-${System.currentTimeMillis()}"
        val first = context.worldBuilder().create().use { world ->
            world.connection.waitForChunksRender()
            val onServer = world.server.computeOnServer<List<String>, RuntimeException> { Singleplayer.onServer(it) }
            check(onServer.isEmpty()) { onServer.joinToString("; ") }
            val (opened, problems) = Singleplayer.open()
            check(problems.isEmpty() && opened != null) { problems.joinToString("; ") }
            val marked = Singleplayer.mark(opened, mark)
            check(marked.isEmpty()) { marked.joinToString("; ") }
            check(Singleplayer.marked(opened, mark)) { "the first world doesn't know its own token" }
            opened
        }
        val closed = Singleplayer.closed(first)
        check(closed.isEmpty()) { closed.joinToString("; ") }

        context.worldBuilder().create().use { world ->
            world.connection.waitForChunksRender()
            val (opened, problems) = Singleplayer.open()
            check(problems.isEmpty() && opened != null) { problems.joinToString("; ") }
            check(!Singleplayer.marked(opened, mark)) { "the second world knows the first world's token" }
        }
    }
}
