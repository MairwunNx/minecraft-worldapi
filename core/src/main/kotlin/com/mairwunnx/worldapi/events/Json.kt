package com.mairwunnx.worldapi.events

import com.mairwunnx.worldapi.api.ApiJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** A model as an event's payload. */
fun <T> KSerializer<T>.json(value: T): JsonObject = ApiJson.encodeToJsonElement(this, value).jsonObject
