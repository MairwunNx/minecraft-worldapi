package com.mairwunnx.worldapi.fabric

import com.mairwunnx.worldapi.modcore.Loader
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.commands.CommandSourceStack
import java.nio.file.Files
import java.nio.file.Path

/** Fabric as the shared mod's loader: Fabric Loader's mod list and fabric-permissions-api. */
object OnFabric : Loader {
    override val id = "fabric"
    override val software = "Fabric"
    override val softwareVersion: String get() = FabricLoader.getInstance().getModContainer("fabricloader").map { it.metadata.version.friendlyString }.orElse("unknown")
    override val version: String get() = FabricLoader.getInstance().getModContainer("worldapi").map { it.metadata.version.friendlyString }.orElse("dev")
    override val config: Path get() = FabricLoader.getInstance().configDir

    /** What the loader's built-ins are called: the list shows mods a player installed. */
    private val BUILT_IN = setOf("minecraft", "java", "fabricloader", "mixinextras")

    override fun mods(): List<Loader.ModInfo> = FabricLoader.getInstance().allMods
        .filter { mod -> mod.metadata.type != "builtin" && mod.metadata.id !in BUILT_IN && mod.containingMod.isEmpty }
        .map {
            val meta = it.metadata
            Loader.ModInfo(meta.id, meta.name, meta.version.friendlyString, meta.description.takeIf(String::isNotBlank), meta.authors.map { person -> person.name },
                meta.contact.get("homepage").orElse(null))
        }

    private val owners = HashMap<String, String?>()

    /** The mod whose root paths hold the class; a module of Fabric API counts as Fabric API, the mod that carries it. */
    override fun owner(type: Class<*>): String? {
        // A lambda's class is its host's with $$Lambda after it; the host is what a jar holds.
        val host = type.name.substringBefore("$$")
        if (host in owners) return owners[host]
        val path = host.replace('.', '/') + ".class"
        val mod = FabricLoader.getInstance().allMods.firstOrNull { mod -> mod.rootPaths.any { root -> runCatching { Files.exists(root.resolve(path)) }.getOrDefault(false) } }
        var top = mod
        while (top != null && top.containingMod.isPresent) top = top.containingMod.get()
        return top?.metadata?.id.also { owners[host] = it }
    }

    // fabric-permissions-api comes with LuckPerms; its classes are touched only when it's loaded.
    private val permissions by lazy { FabricLoader.getInstance().isModLoaded("fabric-permissions-api-v0") }

    override fun permission(source: CommandSourceStack, permission: String, fallback: Boolean) =
        if (permissions) me.lucko.fabric.api.permissions.v0.Permissions.check(source, permission, fallback) else fallback
}
