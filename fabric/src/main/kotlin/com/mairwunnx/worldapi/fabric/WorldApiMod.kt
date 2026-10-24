package com.mairwunnx.worldapi.fabric

import net.fabricmc.api.DedicatedServerModInitializer

/** WorldAPI on Fabric: only a frame so far, so it loads and does nothing. The Paper plugin is the one that serves. */
class WorldApiMod : DedicatedServerModInitializer {
    override fun onInitializeServer() {}
}
