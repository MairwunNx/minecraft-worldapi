package com.mairwunnx.worldapi.events

import com.mairwunnx.worldapi.api.ApiJson
import com.mairwunnx.worldapi.game.ref
import com.mairwunnx.worldapi.game.view
import com.mairwunnx.worldapi.game.worldName
import com.mairwunnx.worldapi.model.ItemView
import com.mairwunnx.worldapi.model.LocationView
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.Vec3

/** Where an entity stands, without its rotation, as Paper's `Location.json` writes it. */
fun Entity.locationJson(): JsonElement = ApiJson.encodeToJsonElement(LocationView.serializer(), view(withRotation = false))

fun locationJson(level: ServerLevel, pos: Vec3): JsonElement {
    fun short(value: Double) = Math.round(value * 100) / 100.0
    return ApiJson.encodeToJsonElement(LocationView.serializer(), LocationView(level.worldName, short(pos.x), short(pos.y), short(pos.z)))
}

fun ItemStack?.json(level: ServerLevel): JsonElement? = view(registries = level.server.registryAccess())?.let { ApiJson.encodeToJsonElement(ItemView.serializer(), it) }

/** A player event: the player, their world, and the subject that state topics coalesce on. */
fun ServerPlayer.event(topic: Topic, data: JsonObject) = ApiEvent(topic, data, ref(), level().worldName, uuid.toString())
