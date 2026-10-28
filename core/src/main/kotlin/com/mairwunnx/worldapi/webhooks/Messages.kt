package com.mairwunnx.worldapi.webhooks

import com.mairwunnx.worldapi.config.WEBHOOK_LANGUAGES
import com.mairwunnx.worldapi.events.ApiEvent
import com.mairwunnx.worldapi.events.Topic
import kotlinx.serialization.json.JsonPrimitive
import java.util.Properties

/** What a chat webhook writes for an event, in its language, from `webhooks/messages_<language>.properties`. */
object Messages {
    private val bundles: Map<String, Properties> = WEBHOOK_LANGUAGES.associateWith { language ->
        Properties().apply {
            Messages::class.java.getResourceAsStream("/webhooks/messages_$language.properties")!!.reader(Charsets.UTF_8).use(::load)
        }
    }

    /** The keys a webhook's own `messages` may set. */
    val keys: List<String> = bundles.getValue("en").stringPropertyNames().filter { it != "test" }.sorted()

    /** The default text for [key] in [language], for a form that shows what a custom message replaces. */
    fun default(language: String, key: String): String? = template(language, key)

    private fun template(language: String, key: String): String? = bundles[language]?.getProperty(key) ?: bundles.getValue("en").getProperty(key)

    /** The test message a new webhook sends. */
    fun test(language: String) = template(language, "test")!!

    /** The message for [event], or null for one not worth a message, such as a server save. */
    fun text(event: ApiEvent, language: String, custom: Map<String, String> = emptyMap()): String? {
        val values = buildMap {
            put("topic", event.topic.id)
            event.player?.name?.let { put("player", it) }
            event.world?.let { put("world", it) }
            event.data.forEach { (key, value) -> if (value is JsonPrimitive) put(key, value.content) }
        }
        val key = when (event.topic) {
            Topic.PLAYER_JOIN -> if (values["firstJoin"] == "true") "player.join.first" else "player.join"
            Topic.SERVER_STATUS -> when (values["status"]) {
                "started" -> "server.status.started"
                "stopping" -> "server.status.stopping"
                else -> return null
            }
            else -> event.topic.id
        }
        val fallback = if ("player" in values) "event.player" else "event"
        // Its own words first, then the language's, then the plain line: each only if the event holds what it names.
        val template = listOfNotNull(custom[key], template(language, key)).firstOrNull { needed(it).all(values::containsKey) }
            ?: custom[fallback] ?: template(language, fallback)!!
        return fill(template, values)
    }

    private val PLACEHOLDER = Regex("\\{(\\w+)}")

    private fun needed(template: String) = PLACEHOLDER.findAll(template).map { it.groupValues[1] }.toList()

    private fun fill(template: String, values: Map<String, String>) = PLACEHOLDER.replace(template) { values[it.groupValues[1]] ?: it.value }
}
