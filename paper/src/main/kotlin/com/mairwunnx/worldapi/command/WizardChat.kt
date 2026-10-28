package com.mairwunnx.worldapi.command

import com.mairwunnx.worldapi.bukkit.plain
import com.mairwunnx.worldapi.connections.WebhookWizard
import io.papermc.paper.event.player.AsyncChatEvent
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener

/**
 * Takes the secret an operator pastes for the webhook wizard before anything else sees it: first of all listeners,
 * cancelled, so no player, chat plugin, log, or WorldAPI's own chat topic gets it.
 */
class WizardChat(private val wizard: () -> WebhookWizard) : Listener {
    @EventHandler(priority = EventPriority.LOWEST)
    fun onChat(event: AsyncChatEvent) {
        if (wizard().chat(event.player.uniqueId, event.player.name, event.message().plain())) event.isCancelled = true
    }
}
