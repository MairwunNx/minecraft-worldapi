package com.mairwunnx.worldapi.smoke

import com.mairwunnx.worldapi.expansion.v2.Place
import org.bukkit.plugin.java.JavaPlugin

/**
 * A plugin for the smoke test only, never released: [FoundExpansion], which WorldAPI finds through META-INF/services,
 * [CodeExpansion], which this plugin registers and publishes an event of on the first tick, and [LegacyExpansion] of API 1.
 */
class SmokePlugin : JavaPlugin() {
    override fun onEnable() {
        CodeExpansion.register()
        server.scheduler.runTask(this, Runnable {
            logger.info("Published ping: ${CodeExpansion.ping.publish(Ping(1, Place("world", 0.0, 64.0, 0.0)))}")
        })
    }
}
