package com.mairwunnx.worldapi.bukkit

import com.mairwunnx.worldapi.api.badRequest
import com.mairwunnx.worldapi.model.TextFormat
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer

fun Component.plain(): String = PlainTextComponentSerializer.plainText().serialize(this)

fun Component?.plainOrNull(): String? = this?.plain()

fun text(value: String, format: TextFormat?, maxLength: Int): Component {
    if (value.length > maxLength) badRequest("Text is longer than $maxLength characters", "text_too_long")
    return when (format ?: TextFormat.PLAIN) {
        TextFormat.PLAIN -> Component.text(value)
        TextFormat.MINIMESSAGE -> MiniMessage.miniMessage().deserialize(value)
    }
}
