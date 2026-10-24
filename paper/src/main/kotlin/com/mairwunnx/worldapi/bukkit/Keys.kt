package com.mairwunnx.worldapi.bukkit

import com.mairwunnx.worldapi.api.badRequest
import io.papermc.paper.registry.RegistryAccess
import io.papermc.paper.registry.RegistryKey
import org.bukkit.Keyed
import org.bukkit.NamespacedKey
import java.time.Instant
import java.util.UUID

/** Parses `stone` or `minecraft:stone`; the namespace defaults to minecraft. */
fun key(value: String, what: String = "key"): NamespacedKey =
    NamespacedKey.fromString(value.lowercase()) ?: badRequest("\"$value\" isn't a valid $what")

fun <T : Keyed> registryEntry(registry: RegistryKey<T>, value: String, what: String): T =
    RegistryAccess.registryAccess().getRegistry(registry).get(key(value, what))
        ?: badRequest("Unknown $what \"$value\"", "unknown_$what")

val Keyed.id: String get() = key.asString()

fun uuidOrNull(value: String): UUID? = runCatching { UUID.fromString(value) }.getOrNull()

fun iso(millis: Long): String? = if (millis > 0) Instant.ofEpochMilli(millis).toString() else null
