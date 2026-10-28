package com.mairwunnx.worldapi.game

import com.mairwunnx.worldapi.connections.ChatLine
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent

/** The wizards' lines as the game's chat components, one message per line, as Paper shows them. */
fun List<ChatLine>.components(): List<Component> = map { line ->
    when (line) {
        is ChatLine.Text -> Component.literal(line.text).withStyle(when (line.tone) {
            ChatLine.Tone.GOOD -> ChatFormatting.GREEN
            ChatLine.Tone.BAD -> ChatFormatting.RED
            ChatLine.Tone.HINT -> ChatFormatting.GRAY
            ChatLine.Tone.PLAIN -> ChatFormatting.WHITE
        })
        is ChatLine.Buttons -> line.buttons.foldIndexed(Component.empty()) { index, row, button ->
            val click: ClickEvent? = button.run?.let { ClickEvent.RunCommand(it) } ?: button.copy?.let { ClickEvent.CopyToClipboard(it) }
                ?: button.url?.let { ClickEvent.OpenUrl(java.net.URI.create(it)) }
            if (index > 0) row.append(" ")
            row.append(Component.literal(button.label).withStyle { it.withColor(ChatFormatting.AQUA).withClickEvent(click).withHoverEvent(HoverEvent.ShowText(Component.literal(button.hover))) })
        }
    }
}
