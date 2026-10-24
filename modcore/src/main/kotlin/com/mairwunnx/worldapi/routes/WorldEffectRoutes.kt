package com.mairwunnx.worldapi.routes

import com.mairwunnx.worldapi.api.Scope
import com.mairwunnx.worldapi.api.badRequest
import com.mairwunnx.worldapi.game.loadBlockChunk
import com.mairwunnx.worldapi.game.toTarget
import com.mairwunnx.worldapi.game.world
import com.mairwunnx.worldapi.game.worldName
import com.mairwunnx.worldapi.model.Done
import com.mairwunnx.worldapi.model.ExplosionRequest
import com.mairwunnx.worldapi.model.LightningRequest
import com.mairwunnx.worldapi.model.ParticleRequest
import com.mairwunnx.worldapi.model.SoundRequest
import net.minecraft.core.Holder
import net.minecraft.core.particles.SimpleParticleType
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.level.Level

val worldEffectRoutes = endpoints("World effects", Scope.SERVER_READ) {
    post<ExplosionRequest, Done>("/worlds/{world}/explosions", "Make an explosion", Scope.WORLD_WRITE) { request ->
        if (request.power !in 0f..config.limits.maxExplosionPower) badRequest("power must be between 0 and ${config.limits.maxExplosionPower}")
        val target = main { request.location.toTarget(api.server, fallback = world()) }
        loadBlockChunk(target.level, target.block.x, target.block.z)
        auditTarget = "${target.level.worldName} ${target.block.x} ${target.block.y} ${target.block.z} power ${request.power}"
        main {
            // As Bukkit's createExplosion: blocks break as a mob's blast breaks them, or not at all.
            val interaction = if (request.breakBlocks) Level.ExplosionInteraction.MOB else Level.ExplosionInteraction.NONE
            target.level.explode(null, target.pos.x, target.pos.y, target.pos.z, request.power, request.fire, interaction)
            Done(true)
        }
    }

    post<LightningRequest, Done>("/worlds/{world}/lightning", "Strike lightning", Scope.WORLD_WRITE) { request ->
        val target = main { request.location.toTarget(api.server, fallback = world()) }
        loadBlockChunk(target.level, target.block.x, target.block.z)
        auditTarget = "${target.level.worldName} ${target.block.x} ${target.block.y} ${target.block.z}"
        main {
            val bolt = EntityTypes.LIGHTNING_BOLT.create(target.level, EntitySpawnReason.COMMAND) ?: badRequest("Lightning can't strike here")
            bolt.snapTo(target.pos.x, target.pos.y, target.pos.z)
            bolt.setVisualOnly(request.effectOnly)
            target.level.addFreshEntity(bolt)
            Done()
        }
    }

    post<SoundRequest, Done>("/worlds/{world}/sounds", "Play a sound at a spot", Scope.WORLD_WRITE) { request ->
        val target = main { request.location.toTarget(api.server, fallback = world()) }
        if (request.volume !in 0f..16f || request.pitch !in 0.5f..2f) badRequest("volume must be 0–16 and pitch 0.5–2")
        val category = SoundSource.entries.firstOrNull { it.name.equals(request.category, ignoreCase = true) } ?: badRequest("Unknown sound category")
        val sound = Identifier.tryParse(request.sound.lowercase()) ?: badRequest("\"${request.sound}\" isn't a valid sound")
        main {
            val level = target.level
            level.playSeededSound(null, target.pos.x, target.pos.y, target.pos.z, Holder.direct(SoundEvent.createVariableRangeEvent(sound)), category, request.volume, request.pitch, level.random.nextLong())
            Done()
        }
    }

    post<ParticleRequest, Done>("/worlds/{world}/particles", "Show particles at a spot", Scope.WORLD_WRITE) { request ->
        if (request.count !in 1..config.limits.maxParticleCount) badRequest("count must be between 1 and ${config.limits.maxParticleCount}")
        val target = main { request.location.toTarget(api.server, fallback = world()) }
        main {
            val id = Identifier.tryParse(request.particle.lowercase()) ?: badRequest("\"${request.particle}\" isn't a valid particle")
            val particle = BuiltInRegistries.PARTICLE_TYPE.getOptional(id).orElse(null) ?: badRequest("Unknown particle \"${request.particle}\"", "unknown_particle")
            if (particle !is SimpleParticleType) badRequest("${request.particle} needs extra data, which the API doesn't support yet")
            val offset = request.offset
            target.level.sendParticles(particle, target.pos.x, target.pos.y, target.pos.z, request.count, offset.x, offset.y, offset.z, request.speed)
            Done()
        }
    }
}
