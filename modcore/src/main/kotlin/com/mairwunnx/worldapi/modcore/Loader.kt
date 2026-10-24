package com.mairwunnx.worldapi.modcore

import java.nio.file.Path

/**
 * What a loader hands modcore: its id and name, WorldAPI's version, and where configs live. Grows with the mods; a
 * difference between Fabric and NeoForge goes here, never into modcore's code as an `if`.
 */
interface Loader {
    /** `fabric` or `neoforge`. */
    val id: String

    /** The loader's name as people know it, like `Fabric`. */
    val name: String

    /** WorldAPI's own version, from the mod's metadata. */
    val version: String

    /** The game's `config/` folder; WorldAPI's files go to `config/worldapi/`. */
    val config: Path
}
