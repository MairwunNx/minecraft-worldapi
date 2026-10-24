package com.mairwunnx.worldapi.bukkit

import com.mairwunnx.worldapi.api.badRequest
import com.mairwunnx.worldapi.model.EffectRequest
import com.mairwunnx.worldapi.model.EffectView
import io.papermc.paper.registry.RegistryKey
import org.bukkit.entity.LivingEntity
import org.bukkit.potion.PotionEffect

fun PotionEffect.view() = EffectView(type.key.asString(), amplifier, duration, isAmbient, hasParticles(), hasIcon())

fun LivingEntity.effectViews() = activePotionEffects.map { it.view() }.sortedBy { it.type }

fun EffectRequest.toEffect(): PotionEffect {
    if (amplifier !in 0..255) badRequest("amplifier must be 0–255")
    val ticks = durationSeconds?.let {
        if (it <= 0 || it > 1_000_000) badRequest("durationSeconds must be between 1 and 1000000")
        it * 20
    } ?: PotionEffect.INFINITE_DURATION
    return PotionEffect(registryEntry(RegistryKey.MOB_EFFECT, type, "effect"), ticks, amplifier, ambient, particles, icon)
}
