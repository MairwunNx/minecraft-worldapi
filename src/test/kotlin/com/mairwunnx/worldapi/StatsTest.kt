package com.mairwunnx.worldapi

import com.mairwunnx.worldapi.api.HttpServer
import com.mairwunnx.worldapi.config.WorldApiConfig
import com.mairwunnx.worldapi.routes.statsRoutes
import com.mairwunnx.worldapi.stats.Board
import com.mairwunnx.worldapi.stats.Experience
import com.mairwunnx.worldapi.stats.PlayerData
import com.mairwunnx.worldapi.ws.WebSocketHub
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.zip.GZIPOutputStream
import java.util.logging.Logger
import com.mairwunnx.worldapi.stats.StatRef
import com.mairwunnx.worldapi.stats.Statistics
import com.mairwunnx.worldapi.stats.vanillaKey
import com.mairwunnx.worldapi.stats.rank
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StatsTest {
    private val stats = mapOf(
        "minecraft:custom" to mapOf("minecraft:play_time" to 144_000L, "minecraft:damage_dealt" to 205L, "minecraft:walk_one_cm" to 250_000L),
        "minecraft:mined" to mapOf("minecraft:stone" to 10L, "minecraft:diamond_ore" to 2L, "minecraft:deepslate_diamond_ore" to 3L, "minecraft:ancient_debris" to 4L),
    )

    @Test
    fun `statistic refs parse in both spellings`() {
        assertEquals(StatRef("minecraft:custom", "minecraft:jump"), StatRef.parse("custom:jump"))
        assertEquals(StatRef("minecraft:mined", "*"), StatRef.parse("mined:*"))
        assertEquals(StatRef("minecraft:killed", "minecraft:zombie"), StatRef.parse("minecraft:killed/minecraft:zombie"))
        assertNull(StatRef.parse("nope:jump"))
        assertNull(StatRef.parse("custom:"))
        assertNull(StatRef.parse("custom:a b"))
        assertEquals("mined:diamond_ore", StatRef.parse("mined:diamond_ore").toString())
    }

    @Test
    fun `refs read single values and whole categories`() {
        assertEquals(19, StatRef.parse("mined:*")!!.read(stats))
        assertEquals(0, StatRef.parse("killed:*")!!.read(stats))
        assertEquals(144_000, StatRef.parse("custom:play_time")!!.read(stats))
    }

    @Test
    fun `presets convert units like the site does`() {
        assertEquals(2.0, Board.of("playtime")!!.score(stats, 0))
        assertEquals(5.0, Board.of("diamonds")!!.score(stats, 0))
        assertEquals(19.0, Board.of("blocks")!!.score(stats, 0))
        assertEquals(4.0, Board.of("netherite")!!.score(stats, 0))
        assertEquals(10.0, Board.of("damage")!!.score(stats, 0))
        assertEquals(2.5, Board.of("travel")!!.score(stats, 0))
        assertEquals(7.0, Board.of("advancements")!!.score(stats, 7))
        assertEquals("custom:jump", Board.of("custom:jump")!!.id)
        assertNull(Board.of("bogus"))
    }

    @Test
    fun `live custom statistics use the ids from the stats files`() {
        assertEquals("minecraft:play_time", vanillaKey(org.bukkit.Statistic.PLAY_ONE_MINUTE))
        assertEquals("minecraft:deaths", vanillaKey(org.bukkit.Statistic.DEATHS))
    }

    @Test
    fun `ties share a place and the next place skips ahead`() {
        val ranked = rank(listOf("b" to 5.0, "a" to 5.0, "c" to 9.0, "d" to 1.0, "e" to 0.0)) { it }
        assertEquals(listOf(1 to "c", 2 to "a", 2 to "b", 4 to "d"), ranked.map { it.place to it.item })
    }

    @Test
    fun `advancement files count finished ones without recipes and roots`() {
        val text = """
            {
              "minecraft:story/root": {"criteria": {}, "done": true},
              "minecraft:story/mine_stone": {"criteria": {}, "done": true},
              "minecraft:recipes/misc/torch": {"criteria": {}, "done": true},
              "minecraft:story/smelt_iron": {"criteria": {}, "done": false},
              "DataVersion": 4000
            }
        """.trimIndent()
        assertEquals(1, Statistics::class.java.getDeclaredConstructor(org.bukkit.Server::class.java, com.mairwunnx.worldapi.core.MainThread::class.java)
            .newInstance(fakeServer(), com.mairwunnx.worldapi.core.MainThread.Immediate).countDone(text))
    }

    @Test
    fun `experience adds up pickups before and after they reach the store`() = TestApi(WorldApiConfig()).use { fake ->
        val steve = UUID.randomUUID()
        val alex = UUID.randomUUID()
        val experience = fake.api.experience
        experience.add(steve, "Steve", 7)
        experience.add(alex, "Alex", 3)
        experience.add(alex, "Alex", -5)
        experience.flush()
        experience.add(steve, "Steve", 5)
        val totals = runBlocking { experience.totals() }.associate { it.name to it.points }
        assertEquals(mapOf("Steve" to 12L, "Alex" to 3L), totals)
    }

    @Test
    fun `new presets answer on the public leaderboard routes`() = TestApi(WorldApiConfig()).use { fake ->
        val hub = WebSocketHub(fake.ref::get, { null }) { _, _ -> emptyList() }
        fake.api.experience.add(UUID.randomUUID(), "Steve", 120)
        fake.api.experience.add(UUID.randomUUID(), "Alex", 45)
        fake.api.experience.flush()
        testApplication {
            application { HttpServer(fake.api, statsRoutes, hub, Logger.getAnonymousLogger()).configure(this) }
            fun json(text: String) = Json.parseToJsonElement(text).jsonObject

            val ids = Json.parseToJsonElement(client.get("/api/v1/leaderboards").bodyAsText()).jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
            assertTrue("netherite" in ids && "experience" in ids)

            val netherite = client.get("/api/v1/leaderboards/netherite")
            assertEquals(HttpStatusCode.OK, netherite.status)
            assertEquals("debris", json(netherite.bodyAsText())["unit"]!!.jsonPrimitive.content)

            val experience = json(client.get("/api/v1/leaderboards/experience?limit=1").bodyAsText())
            assertEquals("xp", experience["unit"]!!.jsonPrimitive.content)
            assertEquals("2", experience["total"]!!.jsonPrimitive.content)
            val first = experience["entries"]!!.jsonArray.single().jsonObject
            assertEquals("Steve", first["player"]!!.jsonObject["name"]!!.jsonPrimitive.content)
            assertEquals(120.0, first["value"]!!.jsonPrimitive.content.toDouble())
        }
    }

    private fun Experience.points() = runBlocking { totals() }.associate { it.name to it.points }

    /** A gzipped playerdata file with some tags of every kind around `XpTotal`, as vanilla writes it. */
    private fun playerData(folder: File, uuid: UUID, xpTotal: Int?) = File(folder, "$uuid.dat").also { file ->
        DataOutputStream(GZIPOutputStream(file.outputStream())).use { out ->
            fun tag(type: Int, name: String) = out.writeByte(type).also { out.writeUTF(name) }
            tag(10, "")
            tag(9, "Pos"); out.writeByte(6); out.writeInt(3); repeat(3) { out.writeDouble(1.5) }
            tag(10, "abilities"); tag(1, "flying"); out.writeByte(0); tag(5, "walkSpeed"); out.writeFloat(0.1f); out.writeByte(0)
            tag(11, "UUID"); out.writeInt(4); repeat(4) { out.writeInt(7) }
            tag(8, "Dimension"); out.writeUTF("minecraft:overworld")
            tag(9, "Inventory"); out.writeByte(10); out.writeInt(1); tag(8, "id"); out.writeUTF("minecraft:stone"); tag(3, "count"); out.writeInt(1); out.writeByte(0)
            tag(12, "seeds"); out.writeInt(1); out.writeLong(42)
            tag(7, "bytes"); out.writeInt(2); out.write(byteArrayOf(1, 2))
            tag(3, "XpLevel"); out.writeInt(30)
            if (xpTotal != null) { tag(3, "XpTotal"); out.writeInt(xpTotal) }
            tag(2, "Air"); out.writeShort(300)
            out.writeByte(0)
        }
    }

    @Test
    fun `playerdata gives XpTotal and skips broken files`() {
        val folder = Files.createTempDirectory("playerdata").toFile()
        assertEquals(1395, PlayerData.xpTotal(playerData(folder, UUID.randomUUID(), 1395)))
        assertNull(PlayerData.xpTotal(playerData(folder, UUID.randomUUID(), null)))
        assertNull(PlayerData.xpTotal(File(folder, "missing.dat")))
        val broken = File(folder, "${UUID.randomUUID()}.dat").apply { writeBytes(byteArrayOf(0x1f, 0x8b.toByte(), 1, 2, 3)) }
        assertNull(PlayerData.xpTotal(broken))
        val level = File(folder.parentFile, "level-${UUID.randomUUID()}").apply { File(this, "players/data").mkdirs() }
        assertEquals(File(level, "players/data"), PlayerData.folder(level))
    }

    @Test
    fun `the baseline is taken once and pickups add on top`() = TestApi(WorldApiConfig()).use { fake ->
        val steve = UUID.randomUUID()
        val experience = fake.api.experience
        experience.seed(steve, "Steve", 300)
        experience.seed(steve, "Steve", 900)
        experience.add(steve, "Steve", 10)
        experience.flush()
        assertEquals(mapOf("Steve" to 310L), experience.points())
    }

    @Test
    fun `a restart neither adds the baseline again nor rereads the files`() {
        val db = Files.createTempFile("worldapi", ".db")
        val folder = Files.createTempDirectory("playerdata").toFile()
        val steve = UUID.randomUUID()
        val alex = UUID.randomUUID()
        playerData(folder, steve, 300)
        playerData(folder, alex, 0)
        val names = mapOf(steve to "Steve", alex to "Alex")
        sqliteStore(db).use { store ->
            val experience = Experience(store)
            assertEquals(2, experience.seedFromFiles(folder, names))
            experience.add(steve, "Steve", 25)
            experience.flush()
            assertEquals(mapOf("Steve" to 325L), experience.points())
        }
        playerData(folder, steve, 325)
        sqliteStore(db).use { store ->
            val experience = Experience(store)
            assertTrue(experience.hasBaseline(steve) && experience.hasBaseline(alex))
            assertEquals(0, experience.seedFromFiles(folder, names))
            experience.seed(steve, "Steve", 325)
            assertEquals(mapOf("Steve" to 325L), experience.points())
        }
    }

    @Test
    fun `after 1_1_0 counted on its own the total becomes the larger of the two, not their sum`() {
        val db = Files.createTempFile("worldapi", ".db")
        val steve = UUID.randomUUID()
        val alex = UUID.randomUUID()
        // 1.1.0 counted pickups with no baseline: Steve gathered 50 since, Alex 500 and then died.
        sqliteStore(db).use { store ->
            store.blocking { exec("INSERT INTO experience (player_uuid, player_name, gained) VALUES ('$steve', 'Steve', 50), ('$alex', 'Alex', 500)") }
        }
        sqliteStore(db).use { store ->
            val experience = Experience(store)
            assertFalse(experience.hasBaseline(steve))
            experience.seed(steve, "Steve", 300)
            experience.seed(alex, "Alex", 120)
            assertEquals(mapOf("Steve" to 300L, "Alex" to 500L), experience.points())
        }
    }

    @Test
    fun `a player who joins later gets a baseline then`() = TestApi(WorldApiConfig()).use { fake ->
        val folder = Files.createTempDirectory("playerdata").toFile()
        val steve = UUID.randomUUID()
        val newcomer = UUID.randomUUID()
        playerData(folder, steve, 300)
        File(folder, "$newcomer.dat").writeText("not nbt")
        val experience = fake.api.experience
        // The newcomer's file is broken, so the seeding at startup skips them.
        assertEquals(1, experience.seedFromFiles(folder, mapOf(steve to "Steve", newcomer to "Newcomer")))
        assertFalse(experience.hasBaseline(newcomer))
        experience.seed(newcomer, "Newcomer", 80)
        experience.add(newcomer, "Newcomer", 5)
        experience.flush()
        assertEquals(mapOf("Steve" to 300L, "Newcomer" to 85L), experience.points())
    }
}
