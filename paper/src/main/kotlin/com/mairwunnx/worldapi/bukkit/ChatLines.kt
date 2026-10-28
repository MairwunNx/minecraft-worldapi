package com.mairwunnx.worldapi.bukkit

import com.mairwunnx.worldapi.connections.ChatLine
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor

/** The wizards' lines as Adventure components, one message per line. */
fun List<ChatLine>.components(): List<Component> = map { line ->
    when (line) {
        is ChatLine.Text -> Component.text(line.text, when (line.tone) {
            ChatLine.Tone.GOOD -> NamedTextColor.GREEN
            ChatLine.Tone.BAD -> NamedTextColor.RED
            ChatLine.Tone.HINT -> NamedTextColor.GRAY
            ChatLine.Tone.PLAIN -> NamedTextColor.WHITE
        })
        is ChatLine.Buttons -> Component.join(net.kyori.adventure.text.JoinConfiguration.separator(Component.space()), line.buttons.map { button ->
            val click: ClickEvent<*>? = button.run?.let { ClickEvent.runCommand(it) } ?: button.copy?.let { ClickEvent.copyToClipboard(it) } ?: button.url?.let { ClickEvent.openUrl(it) }
            Component.text(button.label, NamedTextColor.AQUA).clickEvent(click).hoverEvent(HoverEvent.showText(Component.text(button.hover)))
        })
    }
}
