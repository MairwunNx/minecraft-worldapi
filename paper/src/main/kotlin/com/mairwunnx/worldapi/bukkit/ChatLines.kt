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

/**
 * The game's dialog with one text field: what's typed in comes back to [answer] as a click action, which no chat or
 * command event and no log sees. It answers once, within 10 minutes.
 */
fun secretDialog(prompt: com.mairwunnx.worldapi.connections.SecretPrompt, answer: (String) -> Unit): io.papermc.paper.dialog.Dialog =
    io.papermc.paper.dialog.Dialog.create { factory ->
        factory.empty()
            .base(
                io.papermc.paper.registry.data.dialog.DialogBase.builder(Component.text(prompt.title))
                    .canCloseWithEscape(true)
                    .body(listOf(io.papermc.paper.registry.data.dialog.body.DialogBody.plainMessage(Component.text(prompt.text))))
                    .inputs(listOf(io.papermc.paper.registry.data.dialog.input.DialogInput.text("secret", Component.text(prompt.label)).width(300).maxLength(512).build()))
                    .build(),
            )
            .type(
                io.papermc.paper.registry.data.dialog.type.DialogType.notice(
                    io.papermc.paper.registry.data.dialog.ActionButton.create(
                        Component.text(prompt.button), null, 150,
                        io.papermc.paper.registry.data.dialog.action.DialogAction.customClick(
                            { view, _ -> answer(view.getText("secret").orEmpty()) },
                            net.kyori.adventure.text.event.ClickCallback.Options.builder().uses(1).lifetime(java.time.Duration.ofMinutes(10)).build(),
                        ),
                    ),
                ),
            )
    }
