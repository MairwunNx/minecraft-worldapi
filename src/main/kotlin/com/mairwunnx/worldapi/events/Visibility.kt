package com.mairwunnx.worldapi.events

import com.mairwunnx.worldapi.api.Principal
import com.mairwunnx.worldapi.api.Scope
import com.mairwunnx.worldapi.config.WorldApiConfig
import com.mairwunnx.worldapi.model.EventView
import kotlinx.serialization.json.JsonObject

/** Decides which events a client may see and strips what privacy settings hide. */
class Visibility(private val principal: Principal, private val config: WorldApiConfig) {
    private val privacy = config.privacy.anonymous

    fun allows(topic: Topic): Boolean {
        topic.extension?.let { extension ->
            if (!config.extensions.enabled) return false
            return if (principal.anonymous) extension.public && !hidden(extension.namespace, topic.id) else principal.has(topic.scope)
        }
        if (topic == Topic.PLAYER_CHAT && !config.events.chat) return false
        if (principal.anonymous && topic.id !in config.websocket.anonymousTopics) return false
        // Anonymous clients get chat and the like through privacy settings, not permissions.
        if (!principal.anonymous && !principal.has(topic.scope)) return false
        if (principal.anonymous && topic.scope !in Scope.anonymous && topic.privacy == Topic.Privacy.NONE) return false
        return when (topic.privacy) {
            Topic.Privacy.NONE, Topic.Privacy.COORDINATES -> true
            Topic.Privacy.COORDINATES_ONLY -> coordinates
            Topic.Privacy.CHAT -> !principal.anonymous || privacy.chat
            Topic.Privacy.INVENTORIES -> inventories
        }
    }

    private val coordinates = principal.has(Scope.PLAYERS_PRIVATE) || (principal.anonymous && privacy.coordinates)
    private val inventories = principal.has(Scope.PLAYERS_PRIVATE) || (principal.anonymous && privacy.inventories)

    /** Whether the server's config keeps a plugin's resource or topic from anonymous clients. */
    fun hidden(namespace: String, id: String) = config.extensions.private.any { it == namespace || it == id }

    /** Events about a vanished player reach admin clients only. */
    fun shows(event: ApiEvent) = !event.hidden || principal.has(Scope.PLAYERS_MODERATE)

    fun redact(view: EventView, topic: Topic): EventView {
        if (coordinates || topic.privacy != Topic.Privacy.COORDINATES) return view
        return view.copy(data = JsonObject(view.data.filterKeys { it !in Topic.locationKeys }))
    }
}
