package com.mairwunnx.worldapi.game

import com.google.gson.JsonParser
import com.mairwunnx.worldapi.api.badRequest
import com.mairwunnx.worldapi.model.TextFormat
import com.mojang.serialization.JsonOps
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.ComponentSerialization

fun Component.plain(): String = string

fun Component?.plainOrNull(): String? = this?.plain()

/**
 * A message in a request as the game's text. MiniMessage goes through Adventure, which the mods carry, and on to the
 * game as JSON, so the tags read as on Paper.
 */
fun text(value: String, format: TextFormat?, maxLength: Int): Component {
    if (value.length > maxLength) badRequest("Text is longer than $maxLength characters", "text_too_long")
    return when (format ?: TextFormat.PLAIN) {
        TextFormat.PLAIN -> Component.literal(value)
        TextFormat.MINIMESSAGE -> {
            val json = GsonComponentSerializer.gson().serialize(MiniMessage.miniMessage().deserialize(value))
            ComponentSerialization.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).result().orElseGet { Component.literal(value) }
        }
    }
}
