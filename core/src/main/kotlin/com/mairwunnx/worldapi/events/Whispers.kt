package com.mairwunnx.worldapi.events

import com.mairwunnx.worldapi.config.Events
import com.mairwunnx.worldapi.model.PlayerRef
import java.util.concurrent.ConcurrentHashMap

/**
 * Private messages players send with a command, `/msg Alex hi` or `/r hi`, read from the command line itself, so it
 * works with the game's own commands and with chat plugins' alike. It can't tell whether the command went through: a
 * muted player's message is recorded too, unless a plugin cancelled the command first.
 */
class Whispers(private val settings: () -> Events) {
    /** Whom each player last wrote to or heard from, by UUID, for reply commands. */
    private val partners = ConcurrentHashMap<String, String>()

    class Whisper(val recipient: PlayerRef, val message: String)

    /**
     * The private message in [command] (without its slash) from [sender], or null when it isn't one, its recipient
     * isn't online, or it's empty. [find] looks an online player up by UUID or name.
     */
    fun parse(sender: String, command: String, find: (String) -> PlayerRef?): Whisper? {
        val events = settings()
        if (!events.whispers) return null
        val line = command.trim().removePrefix("/")
        val label = line.substringBefore(' ')
        val rest = line.substring(label.length).trim()
        // `minecraft:msg` and `essentials:msg` are the same commands.
        val name = label.substringAfter(':').lowercase()
        val (recipient, message) = when {
            events.whisperCommands.any { it.equals(name, ignoreCase = true) } -> {
                val target = rest.substringBefore(' ')
                // A selector such as @p names nobody in particular until the game picks.
                if (target.isEmpty() || target.startsWith("@")) return null
                (find(target) ?: return null) to rest.substring(target.length).trim()
            }
            events.replyCommands.any { it.equals(name, ignoreCase = true) } -> (partners[sender]?.let(find) ?: return null) to rest
            else -> return null
        }
        if (message.isEmpty() || recipient.uuid == sender) return null
        partners[sender] = recipient.uuid
        partners[recipient.uuid] = sender
        return Whisper(recipient, message)
    }

    /** A player who left: nobody answers them with a reply command any more. */
    fun forget(uuid: String) {
        partners.remove(uuid)
        partners.values.removeIf { it == uuid }
    }
}
