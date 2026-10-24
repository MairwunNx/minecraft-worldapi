package com.mairwunnx.worldapi.game

import com.mairwunnx.worldapi.api.badRequest
import com.mairwunnx.worldapi.model.EffectRequest
import com.mairwunnx.worldapi.model.EffectView
import net.minecraft.core.Holder
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.world.effect.MobEffect
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.entity.LivingEntity

fun MobEffectInstance.view() = EffectView(effect.unwrapKey().map { it.identifier().toString() }.orElse("unknown"), amplifier, duration, isAmbient, isVisible, showIcon())

fun LivingEntity.effectViews() = activeEffects.map { it.view() }.sortedBy { it.type }

/** An effect by `speed` or `minecraft:speed`, or 400 `unknown_effect`. */
fun effect(value: String): Holder<MobEffect> {
    val id = Identifier.tryParse(value.lowercase()) ?: badRequest("\"$value\" isn't a valid effect")
    return BuiltInRegistries.MOB_EFFECT.get(id).orElse(null) ?: badRequest("Unknown effect \"$value\"", "unknown_effect")
}

fun EffectRequest.toEffect(): MobEffectInstance {
    if (amplifier !in 0..255) badRequest("amplifier must be 0–255")
    val ticks = durationSeconds?.let {
        if (it <= 0 || it > 1_000_000) badRequest("durationSeconds must be between 1 and 1000000")
        it * 20
    } ?: MobEffectInstance.INFINITE_DURATION
    return MobEffectInstance(effect(type), ticks, amplifier, ambient, particles, icon)
}
