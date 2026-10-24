package com.mairwunnx.worldapi.neoforge

import net.neoforged.api.distmarker.Dist
import net.neoforged.fml.common.Mod

/** WorldAPI on NeoForge: only a frame so far, so it loads and does nothing. The Paper plugin is the one that serves. */
@Mod("worldapi", dist = [Dist.DEDICATED_SERVER])
class WorldApiMod
