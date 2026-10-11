package com.mairwunnx.worldapi.routes

import com.mairwunnx.worldapi.api.Scope
import com.mairwunnx.worldapi.api.badRequest
import com.mairwunnx.worldapi.api.endpoints
import com.mairwunnx.worldapi.bukkit.key
import com.mairwunnx.worldapi.bukkit.loadBlockChunk
import com.mairwunnx.worldapi.bukkit.registryEntry
import com.mairwunnx.worldapi.bukkit.toLocation
import com.mairwunnx.worldapi.bukkit.world
import com.mairwunnx.worldapi.model.Done
import com.mairwunnx.worldapi.model.ExplosionRequest
import com.mairwunnx.worldapi.model.LightningRequest
import com.mairwunnx.worldapi.model.ParticleRequest
import com.mairwunnx.worldapi.model.SoundRequest
import io.papermc.paper.registry.RegistryKey
import org.bukkit.Particle
import org.bukkit.SoundCategory

val worldEffectRoutes = endpoints("World effects") {
    post<ExplosionRequest, Done>("/worlds/{world}/explosions", "Make an explosion", Scope.WRITE_WORLD) { request ->
        if (request.power !in 0f..config.limits.maxExplosionPower) badRequest("power must be between 0 and ${config.limits.maxExplosionPower}")
        val location = request.location.toLocation(api.server, fallback = world())
        loadBlockChunk(location.world, location.blockX, location.blockZ)
        auditTarget = "${location.world.name} ${location.blockX} ${location.blockY} ${location.blockZ} power ${request.power}"
        main { Done(location.world.createExplosion(location, request.power, request.fire, request.breakBlocks)) }
    }

    post<LightningRequest, Done>("/worlds/{world}/lightning", "Strike lightning", Scope.WRITE_WORLD) { request ->
        val location = request.location.toLocation(api.server, fallback = world())
        loadBlockChunk(location.world, location.blockX, location.blockZ)
        auditTarget = "${location.world.name} ${location.blockX} ${location.blockY} ${location.blockZ}"
        main {
            if (request.effectOnly) location.world.strikeLightningEffect(location) else location.world.strikeLightning(location)
            Done()
        }
    }

    post<SoundRequest, Done>("/worlds/{world}/sounds", "Play a sound at a spot", Scope.WRITE_WORLD) { request ->
        val location = request.location.toLocation(api.server, fallback = world())
        if (request.volume !in 0f..16f || request.pitch !in 0.5f..2f) badRequest("volume must be 0–16 and pitch 0.5–2")
        val category = SoundCategory.entries.firstOrNull { it.name.equals(request.category, ignoreCase = true) } ?: badRequest("Unknown sound category")
        val sound = key(request.sound, "sound").asString()
        main {
            location.world.playSound(location, sound, category, request.volume, request.pitch)
            Done()
        }
    }

    post<ParticleRequest, Done>("/worlds/{world}/particles", "Show particles at a spot", Scope.WRITE_WORLD) { request ->
        if (request.count !in 1..config.limits.maxParticleCount) badRequest("count must be between 1 and ${config.limits.maxParticleCount}")
        val location = request.location.toLocation(api.server, fallback = world())
        main {
            val particle = registryEntry(RegistryKey.PARTICLE_TYPE, request.particle, "particle")
            if (particle.dataType != Void::class.java) badRequest("${request.particle} needs extra data, which the API doesn't support yet")
            val offset = request.offset
            location.world.spawnParticle<Any>(particle as Particle, location, request.count, offset.x, offset.y, offset.z, request.speed, null)
            Done()
        }
    }
}
