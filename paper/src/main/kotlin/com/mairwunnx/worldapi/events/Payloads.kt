package com.mairwunnx.worldapi.events

import com.mairwunnx.worldapi.api.ApiJson
import com.mairwunnx.worldapi.bukkit.ref
import com.mairwunnx.worldapi.bukkit.view
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

fun Location.json(): JsonElement = ApiJson.encodeToJsonElement(com.mairwunnx.worldapi.model.LocationView.serializer(), view(withRotation = false))

fun ItemStack?.json(): JsonElement? = view()?.let { ApiJson.encodeToJsonElement(com.mairwunnx.worldapi.model.ItemView.serializer(), it) }

/** A player event: the player, their world, and the subject that state topics coalesce on. */
fun Player.event(topic: Topic, data: JsonObject) = ApiEvent(topic, data, ref(), world.name, uniqueId.toString())
