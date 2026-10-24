package com.mairwunnx.worldapi.neoforge

import com.mairwunnx.worldapi.modcore.Loader
import com.mairwunnx.worldapi.modcore.WorldApiCommand
import net.minecraft.commands.CommandSourceStack
import net.minecraft.server.permissions.Permissions
import net.neoforged.fml.ModList
import net.neoforged.fml.loading.FMLPaths
import net.neoforged.neoforge.server.permission.PermissionAPI
import net.neoforged.neoforge.server.permission.events.PermissionGatherEvent
import net.neoforged.neoforge.server.permission.nodes.PermissionNode
import net.neoforged.neoforge.server.permission.nodes.PermissionTypes
import org.slf4j.LoggerFactory
import java.nio.file.Path

/** NeoForge as the shared mod's loader: FML's mod list and NeoForge's PermissionAPI. */
object OnNeoForge : Loader {
    override val id = "neoforge"
    override val software = "NeoForge"
    override val softwareVersion: String get() = ModList.get().getModContainerById("neoforge").map { it.modInfo.version.toString() }.orElse("unknown")

    // FML wants a number, so a dev build carries 0.0.0-dev; WorldAPI calls it dev on every platform.
    override val version: String
        get() = ModList.get().getModContainerById("worldapi").map { it.modInfo.version.toString() }.orElse("dev").removePrefix("0.0.0-")

    override val config: Path get() = FMLPaths.CONFIGDIR.get()

    override val serverConfigs = listOf("config/neoforge-server.toml", "config/neoforge-common.toml")

    /** The game and the loader itself: the list shows mods a player installed. */
    private val BUILT_IN = setOf("minecraft", "neoforge")

    override fun mods(): List<Loader.ModInfo> = ModList.get().mods
        .filter { it.modId !in BUILT_IN }
        // FML fills a missing description with these words; the list shows none instead.
        .map {
            Loader.ModInfo(
                it.modId, it.displayName, it.version.toString(), it.description.trim().takeIf { text -> text.isNotEmpty() && text != "MISSING DESCRIPTION" },
                (it.config.getConfigElement<Any>("authors").orElse(null) as? String)?.split(',')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty(),
                it.modURL.map { url -> url.toString() }.orElse(null),
            )
        }

    private val owners = HashMap<String, String?>()

    /** The first mod of the mod file whose jar holds the class: FML knows each file's contents. */
    override fun owner(type: Class<*>): String? {
        // A lambda's class is its host's with $$Lambda after it; the host is what a jar holds.
        val host = type.name.substringBefore("$$")
        if (host in owners) return owners[host]
        val path = host.replace('.', '/') + ".class"
        val file = ModList.get().modFiles.firstOrNull { file -> runCatching { file.file.contents.containsFile(path) }.getOrDefault(false) }
        return file?.mods?.firstOrNull()?.modId.also { owners[host] = it }
    }

    /** PermissionAPI answers only for nodes named before the server starts. */
    private val admin = PermissionNode("worldapi", "admin", PermissionTypes.BOOLEAN, { player, _, _ ->
        player?.permissions()?.hasPermission(Permissions.COMMANDS_GAMEMASTER) == true
    })

    fun nodes(event: PermissionGatherEvent.Nodes) {
        event.addNodes(admin)
    }

    // A command block or the console has no player to ask PermissionAPI about.
    override fun permission(source: CommandSourceStack, permission: String, fallback: Boolean): Boolean {
        val player = source.player ?: return fallback
        if (permission != WorldApiCommand.PERMISSION) return fallback
        return try {
            PermissionAPI.getPermission(player, admin)
        } catch (error: Exception) {
            LoggerFactory.getLogger("WorldAPI").warn("Couldn't ask NeoForge's PermissionAPI about $permission", error)
            fallback
        }
    }
}
