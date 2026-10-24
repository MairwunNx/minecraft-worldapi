package com.mairwunnx.worldapi.modcore

import net.minecraft.commands.CommandSourceStack
import net.minecraft.server.level.ServerPlayer
import java.nio.file.Path

/**
 * What differs between the mod loaders, behind one face. Fabric and NeoForge each hand theirs to [Mod] when the game
 * loads the mod; nothing else in modcore touches a loader's API, and a difference goes here, never into modcore's code
 * as an `if`. Everything here may run on the server thread.
 */
interface Loader {
    /** A mod as a player would name it in the server's list. */
    data class ModInfo(
        val id: String,
        val name: String,
        val version: String,
        val description: String?,
        val authors: List<String>,
        val website: String?,
    )

    /** What `/server` says as `platform`: `fabric` or `neoforge`. */
    val id: String

    /** What the server runs, where Paper says Paper: `Fabric` or `NeoForge`. */
    val software: String

    /** The loader's own version, such as Fabric Loader's `0.20.1` or NeoForge's `26.3.0.95`. */
    val softwareVersion: String

    /** WorldAPI's version, as the loader read it from the mod's descriptor. */
    val version: String

    /** The game's `config/` folder; WorldAPI's shared files go to `config/worldapi/`. */
    val config: Path

    /** The loader's own configs for the server, relative to its folder, such as NeoForge's `config/neoforge-server.toml`. */
    val serverConfigs: List<String> get() = emptyList()

    /** The mods a player installed: not the game, Java, the loader, or the libraries mods carry inside them. */
    fun mods(): List<ModInfo>

    /** A permissions mod's answer for a command's sender, or [fallback] without one. */
    fun permission(source: CommandSourceStack, permission: String, fallback: Boolean): Boolean

    /**
     * The id of the mod whose jar holds [type], or null where the loader can't tell: whose a command is, from the class
     * its code lives in, since Brigadier keeps no owner. Server thread.
     */
    fun owner(type: Class<*>): String? = null

    /** Whether a vanish mod hides the player now; server thread. */
    fun vanished(player: ServerPlayer): Boolean = false
}
