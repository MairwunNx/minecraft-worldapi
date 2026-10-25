package com.mairwunnx.worldapi.neoforge.gametest

import com.mairwunnx.worldapi.gametest.Singleplayer
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.world.level.GameType
import net.minecraft.world.level.LevelSettings
import net.minecraft.world.level.WorldDataConfiguration
import net.minecraft.world.level.levelgen.WorldOptions
import net.minecraft.world.level.levelgen.presets.WorldPresets
import net.neoforged.api.distmarker.Dist
import net.neoforged.fml.ModLoader
import net.neoforged.fml.loading.FMLEnvironment
import net.neoforged.neoforge.client.gui.LoadingErrorScreen
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.common.NeoForge
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CompletableFuture

/**
 * A real client in two singleplayer worlds, one after the other, as Fabric's client game test opens them: the checks
 * are [Singleplayer]'s. They run off the client thread, since every request waits for the integrated server; the
 * result goes to `worldapi-check.txt` in the run folder, then the game closes, and `./gradlew
 * :neoforge:runClientGameTest` fails unless it says `ok`.
 */
object ClientCheck {
    fun start() {
        if (System.getProperty("worldapi.clientGameTest") != "true" || FMLEnvironment.getDist() != Dist.CLIENT) return
        System.setProperty("worldapi.discovery", Files.createTempDirectory("worldapi-local").toString())
        NeoForge.EVENT_BUS.addListener { _: ClientTickEvent.Post -> Client.tick() }
    }

    private object Client {
        private val log = LoggerFactory.getLogger("WorldAPI check")
        private val problems = mutableListOf<String>()
        private val mark = "first-${System.currentTimeMillis()}"
        private var stage = 0
        private var joinedTicks = 0
        private var ticks = 0
        private var closeIn = -1
        private var work: CompletableFuture<List<String>>? = null
        private var first: Singleplayer.World? = null

        fun tick() {
            val game = Minecraft.getInstance()
            if (closeIn > 0) {
                if (--closeIn == 0) game.stop()
                return
            }
            if (closeIn == 0) return
            // Four minutes is far more than two worlds take; a check that never ran fails.
            if (++ticks > 20 * 240) return finish(game, listOf("the check didn't finish within four minutes at stage $stage"))
            val screen = game.gui.screen()
            if (screen is LoadingErrorScreen) {
                ModLoader.getLoadingIssues().filter { it.affectedMod()?.modId?.startsWith("worldapi") == true }.forEach { problems += "NeoForge warns about WorldAPI: $it" }
                game.gui.setScreen(TitleScreen())
                return
            }
            if (screen is AccessibilityOnboardingScreen) {
                game.options.onboardingAccessibilityFinished()
                game.gui.setScreen(TitleScreen())
                return
            }
            when (stage) {
                0, 3 -> if (screen is TitleScreen) {
                    create(game)
                    stage++
                }
                1, 4 -> {
                    val server = game.singleplayerServer ?: return
                    if (game.player == null || ++joinedTicks < 40) return
                    joinedTicks = 0
                    val onServer = runCatching { server.submit<List<String>> { Singleplayer.onServer(server) }.get() }.getOrElse { listOf("the server check failed: $it") }
                    problems += onServer
                    val second = stage == 4
                    work = CompletableFuture.supplyAsync {
                        val (world, found) = Singleplayer.open()
                        if (world == null) return@supplyAsync found
                        first = first ?: world
                        found + if (!second) Singleplayer.mark(world, mark) + listOfNotNull("the first world doesn't know its own token".takeUnless { Singleplayer.marked(world, mark) })
                        else listOfNotNull("the second world knows the first world's token".takeIf { Singleplayer.marked(world, mark) })
                    }
                    stage++
                }
                2, 5 -> {
                    val done = work ?: return
                    if (!done.isDone) return
                    problems += runCatching { done.join() }.getOrElse { listOf("the client check failed: $it") }
                    work = null
                    if (stage == 5) return finish(game, problems)
                    game.disconnectWithSavingScreen()
                    stage = 6
                }
                // Leaving a world ends wherever the game decides; once nothing of it is left, open the title screen ourselves.
                6 -> if (game.level == null && game.singleplayerServer == null) {
                    problems += first?.let(Singleplayer::closed) ?: listOf("the first world gave no address")
                    game.gui.setScreen(TitleScreen())
                    stage = 3
                }
            }
        }

        private fun create(game: Minecraft) {
            val name = "worldapi-check-${System.currentTimeMillis()}"
            val settings = LevelSettings(name, GameType.SURVIVAL, LevelSettings.DifficultySettings.DEFAULT, false, WorldDataConfiguration.DEFAULT)
            game.createWorldOpenFlows().createFreshLevel(name, settings, WorldOptions.defaultWithRandomSeed(), WorldPresets::createNormalWorldDimensions, TitleScreen())
        }

        private fun finish(game: Minecraft, found: List<String>) {
            val result = if (found.isEmpty()) "ok" else found.joinToString("\n")
            log.info("Singleplayer check: {}", result)
            File(game.gameDirectory, "worldapi-check.txt").writeText(result + "\n")
            closeIn = 20
        }
    }
}
