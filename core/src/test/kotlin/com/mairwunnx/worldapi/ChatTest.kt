package com.mairwunnx.worldapi

import com.mairwunnx.worldapi.api.Principal
import com.mairwunnx.worldapi.api.Scope
import com.mairwunnx.worldapi.config.Events
import com.mairwunnx.worldapi.config.Privacy
import com.mairwunnx.worldapi.config.Webhook
import com.mairwunnx.worldapi.config.WorldApiConfig
import com.mairwunnx.worldapi.events.Topic
import com.mairwunnx.worldapi.events.Visibility
import com.mairwunnx.worldapi.events.Whispers
import com.mairwunnx.worldapi.model.PlayerRef
import com.mairwunnx.worldapi.store.TextMatch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatTest {
    private val steve = PlayerRef("00000000-0000-0000-0000-000000000001", "Steve")
    private val alex = PlayerRef("00000000-0000-0000-0000-000000000002", "Alex")
    private val online = listOf(steve, alex)
    private val find: (String) -> PlayerRef? = { id -> online.firstOrNull { it.uuid == id || it.name.equals(id, ignoreCase = true) } }

    @Test
    fun `private messages are read from the commands that send them`() {
        val whispers = Whispers { Events(whispers = true) }
        val sent = whispers.parse(steve.uuid, "/msg alex where are the diamonds?", find)!!
        assertEquals(alex, sent.recipient)
        assertEquals("where are the diamonds?", sent.message)
        assertEquals("hi", whispers.parse(steve.uuid, "minecraft:tell Alex   hi", find)!!.message)
        assertEquals("hi", whispers.parse(steve.uuid, "ESSENTIALS:W Alex hi", find)!!.message)
        // Alex answers the last one who wrote to him.
        val reply = whispers.parse(alex.uuid, "r under spawn", find)!!
        assertEquals(steve, reply.recipient)
        assertEquals("under spawn", reply.message)
        // Not private messages, or nobody to send them to.
        assertNull(whispers.parse(steve.uuid, "msg @p hi", find))
        assertNull(whispers.parse(steve.uuid, "msg Herobrine hi", find))
        assertNull(whispers.parse(steve.uuid, "msg Alex", find))
        assertNull(whispers.parse(steve.uuid, "msg Steve note to self", find))
        assertNull(whispers.parse(steve.uuid, "gamemode creative", find))
        assertNull(Whispers { Events(whispers = true) }.parse(steve.uuid, "r hi", find))
        assertNull(Whispers { Events() }.parse(steve.uuid, "msg Alex hi", find))
        whispers.forget(steve.uuid)
        assertNull(whispers.parse(alex.uuid, "r hi", find))
    }

    @Test
    fun `private messages go to clients with logs read, never to visitors`() {
        val on = WorldApiConfig(events = Events(whispers = true), privacy = Privacy(anonymous = Privacy().anonymous.copy(chat = true)))
        val moderator = Principal("moderator", setOf(Scope.EVENTS_READ, Scope.CHAT_READ, Scope.LOGS_READ), "token")
        val viewer = Principal("viewer", setOf(Scope.EVENTS_READ, Scope.CHAT_READ), "token")
        assertTrue(Visibility(moderator, on).allows(Topic.PLAYER_WHISPER))
        assertFalse(Visibility(viewer, on).allows(Topic.PLAYER_WHISPER))
        assertFalse(Visibility(Principal.anonymous("1.2.3.4"), on.copy(websocket = on.websocket.copy(anonymousTopics = listOf("player.whisper")))).allows(Topic.PLAYER_WHISPER))
        assertFalse(Visibility(moderator, WorldApiConfig()).allows(Topic.PLAYER_WHISPER))
        // A webhook posts them only when it names them.
        assertFalse(Webhook("all", url = "https://1.1.1.1/x", topics = listOf("*", "player.*")).listens(Topic.PLAYER_WHISPER))
        assertTrue(Webhook("dm", url = "https://1.1.1.1/x", topics = listOf("player.whisper")).listens(Topic.PLAYER_WHISPER))
    }

    @Test
    fun `text matches values in any case, without accents and colors`() {
        val chat = buildJsonObject {
            put("message", "§cЁлки, где мои АЛМАЗЫ? Алмазы!")
            putJsonObject("recipient") { put("name", "Café") }
        }
        assertEquals(2, TextMatch(listOf("алмазы")).hits(chat))
        assertEquals(1, TextMatch(listOf("елки")).hits(chat))
        assertEquals(1, TextMatch(listOf("cafe")).hits(chat))
        assertEquals(3, TextMatch(listOf("алмаз", "золото", "ёлки")).hits(chat))
        assertNull(TextMatch(listOf("алмаз", "золото"), all = true).hits(chat))
        // Keys aren't text.
        assertNull(TextMatch(listOf("message")).hits(chat))
        assertTrue(TextMatch(listOf(" ", "")).empty)
    }
}
