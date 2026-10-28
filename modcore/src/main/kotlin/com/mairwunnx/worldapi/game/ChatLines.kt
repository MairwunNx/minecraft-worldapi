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

/**
 * The game's dialog with one text field, as Paper shows it: its button sends what's typed as a click action named
 * `worldapi:secret/<nonce>`, which [com.mairwunnx.worldapi.modcore.Hooks.dialogAnswer] takes before chat or a log sees it.
 */
fun secretDialog(prompt: com.mairwunnx.worldapi.connections.SecretPrompt): net.minecraft.server.dialog.Dialog {
    val input = net.minecraft.server.dialog.Input(
        "secret",
        net.minecraft.server.dialog.input.TextInput(300, Component.literal(prompt.label), true, "", 512, java.util.Optional.empty()),
    )
    val common = net.minecraft.server.dialog.CommonDialogData(
        Component.literal(prompt.title), java.util.Optional.empty(), true, false, net.minecraft.server.dialog.DialogAction.CLOSE,
        listOf(net.minecraft.server.dialog.body.PlainMessage(Component.literal(prompt.text), 300)), listOf(input),
    )
    val send = net.minecraft.server.dialog.action.CustomAll(net.minecraft.resources.Identifier.fromNamespaceAndPath("worldapi", "secret/${prompt.nonce}"), java.util.Optional.empty())
    return net.minecraft.server.dialog.NoticeDialog(
        common,
        net.minecraft.server.dialog.ActionButton(net.minecraft.server.dialog.CommonButtonData(Component.literal(prompt.button), 150), java.util.Optional.of(send)),
    )
}
