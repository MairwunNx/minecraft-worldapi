package com.mairwunnx.worldapi.gametest

import com.mairwunnx.worldapi.gametest.Api.expect
import com.mairwunnx.worldapi.modcore.Mod
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.item.Items
import net.minecraft.world.level.GameType
import net.minecraft.world.level.block.Blocks

/**
 * WorldAPI on a real server of each loader, through real HTTP: what Paper's answers hold, the game's state read and
 * changed, and the events the game's moments make. The same tests run on Fabric and NeoForge.
 */
class ApiGameTests {
    private val JsonObject.text get() = this.mapValues { it.value.toString() }

    @GameTest(maxTicks = 100)
    fun serverSaysWhereItRuns(helper: GameTestHelper) {
        val loader = Mod.loader.id
        helper.expect(Api.get("/server")) { answer ->
            val server = answer.json.jsonObject
            when {
                answer.status != 200 -> "GET /server failed"
                server["platform"]?.jsonPrimitive?.content != loader -> "platform isn't $loader"
                server["side"]?.jsonPrimitive?.content != "dedicated" -> "side isn't dedicated on a test server"
                server["worlds"]?.jsonArray?.map { it.jsonPrimitive.content }?.contains("overworld") != true -> "the overworld isn't named overworld"
                else -> null
            }
        }
    }

    @GameTest(maxTicks = 100)
    fun worldsAnswerToPaperNames(helper: GameTestHelper) {
        helper.expect(Api.get("/worlds/world_nether")) { answer ->
            if (answer.status != 200 || answer.json.jsonObject["key"]?.jsonPrimitive?.content != "minecraft:the_nether") "world_nether isn't the Nether" else null
        }
    }

    @GameTest(maxTicks = 100)
    fun blocksRoundTrip(helper: GameTestHelper) {
        val pos = helper.absolutePos(BlockPos(1, 1, 1))
        helper.expect(Api.call("PUT", "/worlds/overworld/blocks/${pos.x}/${pos.y}/${pos.z}", """{"block":"minecraft:oak_stairs[facing=east]"}""")) { answer ->
            val state = helper.level.getBlockState(pos)
            when {
                answer.status != 200 -> "PUT a block failed"
                !state.`is`(Blocks.OAK_STAIRS) -> "the world holds ${BuiltInRegistries.BLOCK.getKey(state.block)}"
                answer.json.jsonObject["data"]?.jsonPrimitive?.content?.contains("facing=east") != true -> "the answer's state lost facing=east"
                else -> null
            }
        }
    }

    @GameTest(maxTicks = 100)
    fun playersShowAndChange(helper: GameTestHelper) {
        val player = Api.player(helper)
        val name = player.gameProfile.name()
        helper.expect(Api.call("PATCH", "/players/$name", """{"gameMode":"creative","food":7}""")) { answer ->
            when {
                answer.status != 200 -> "PATCH a player failed"
                player.gameMode() != GameType.CREATIVE -> "the game mode is ${player.gameMode()}"
                player.foodData.foodLevel != 7 -> "food is ${player.foodData.foodLevel}"
                answer.json.jsonObject["state"]?.jsonObject?.get("gameMode")?.jsonPrimitive?.content != "creative" -> "the answer's state says otherwise"
                else -> null
            }.also { player.connection.disconnect(net.minecraft.network.chat.Component.literal("done")) }
        }
    }

    @GameTest(maxTicks = 100)
    fun inventoriesTakeItems(helper: GameTestHelper) {
        val player = Api.player(helper)
        helper.expect(Api.call("POST", "/players/${player.uuid}/inventory/give", """{"item":{"type":"minecraft:diamond","amount":70}}""")) { answer ->
            val diamonds = (0 until 36).sumOf { slot -> player.inventory.getItem(slot).takeIf { it.`is`(Items.DIAMOND) }?.count ?: 0 }
            when {
                answer.status != 200 -> "giving failed"
                diamonds != 70 -> "the player holds $diamonds diamonds"
                answer.json.jsonObject["given"]?.jsonPrimitive?.int != 70 -> "the answer counts it otherwise"
                else -> null
            }.also { player.connection.disconnect(net.minecraft.network.chat.Component.literal("done")) }
        }
    }

    @GameTest(maxTicks = 100)
    fun entitiesSpawnWhereAsked(helper: GameTestHelper) {
        val at = helper.absoluteVec(net.minecraft.world.phys.Vec3(2.5, 2.0, 2.5))
        helper.expect(Api.call("POST", "/worlds/overworld/entities", """{"type":"minecraft:pig","location":{"world":"overworld","x":${at.x},"y":${at.y},"z":${at.z}}}""")) { answer ->
            val uuids = (answer.json.jsonObject["uuids"] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()
            val pig = uuids.firstOrNull()?.let { helper.level.getEntity(java.util.UUID.fromString(it)) }
            when {
                answer.status != 201 -> "spawning failed"
                pig?.type != EntityTypes.PIG -> "no pig came"
                else -> null
            }.also { pig?.discard() }
        }
    }

    @GameTest(maxTicks = 200)
    fun joinsReachTheHistory(helper: GameTestHelper) {
        val player = Api.player(helper)
        player.connection.disconnect(net.minecraft.network.chat.Component.literal("done"))
        // The store writes on its own thread; the read waits for it.
        helper.runAfterDelay(5) {
            helper.expect(Api.get("/events?type=player.join&player=${player.uuid}")) { answer ->
                val events = (answer.json.jsonObject["items"] as? JsonArray).orEmpty()
                when {
                    answer.status != 200 -> "GET /events failed"
                    events.isEmpty() -> "the join isn't in the history"
                    else -> null
                }
            }
        }
    }

    @GameTest(maxTicks = 100)
    fun commandsKnowTheirMods(helper: GameTestHelper) {
        helper.expect(Api.get("/server/commands?plugin=worldapi")) { answer ->
            val names = answer.body
            if (answer.status != 200 || "\"worldapi\"" !in names) "the catalog doesn't name worldapi's own command" else null
        }
    }

    @GameTest(maxTicks = 100)
    fun scriptsSayTheyArePaperOnly(helper: GameTestHelper) {
        val owner = Mod.runtime!!.api.tokens.issue("scripts-${System.nanoTime()}", listOf("owner"), "test", null)
        helper.expect(Api.call("GET", "/scripts", headers = mapOf("Authorization" to "Bearer $owner"), auth = false)) { answer ->
            if (answer.status != 501 || "not_supported" !in answer.body) "scripts don't answer 501 not_supported" else null
        }
    }

    @GameTest(maxTicks = 100)
    fun expansionsFromOtherModsAnswer(helper: GameTestHelper) {
        helper.expect(Api.get("/extensions/test_found/echo/hello")) { answer ->
            if (answer.status != 200 || "\"hello\"" !in answer.body) "the test mod's expansion didn't answer" else null
        }
    }

    @GameTest(maxTicks = 100)
    fun anonymousClientsGetNoCoordinates(helper: GameTestHelper) {
        val player = Api.player(helper)
        helper.expect(Api.call("GET", "/players/online", auth = false)) { answer ->
            when {
                answer.status != 200 -> "anonymous /players/online failed"
                "\"location\"" in answer.body -> "an anonymous client sees where players stand"
                else -> null
            }.also { player.connection.disconnect(net.minecraft.network.chat.Component.literal("done")) }
        }
    }
}

private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this?.toList() ?: emptyList()
