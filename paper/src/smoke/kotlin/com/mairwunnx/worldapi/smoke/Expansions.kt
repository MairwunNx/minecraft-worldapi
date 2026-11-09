package com.mairwunnx.worldapi.smoke

import com.mairwunnx.worldapi.expansion.v2.Expansion
import com.mairwunnx.worldapi.expansion.v2.Place
import org.bukkit.Bukkit

@JvmRecord data class Echo(val word: String, val limit: Int, val anonymous: Boolean)

@JvmRecord data class Ping(val n: Int, val place: Place)

/** The expansion WorldAPI finds by itself: an object named in META-INF/services. */
object FoundExpansion : Expansion("smoke_found", "WorldAPI smoke", "The smoke test’s expansion") {
    val word = text("word", "Any word")
    val limit = integer("limit", "Any number from 1 to 10", 1, 10, 5)

    val echo = resource("echo/{word}", "The word, the limit, and who asks") { req -> Echo(req[word], req[limit], req.anonymous()) }.query(limit).returns(Echo::class.java).publicly()
}

/** The expansion the plugin registers in code, with an event it publishes on the first tick. */
object CodeExpansion : Expansion("smoke_code") {
    val worlds = resource("worlds", "The worlds’ names") { Bukkit.getWorlds().map { it.name } }.onServerThread()

    val ping = event("ping", Ping::class.java, "Sent once, on the first tick").recorded()
}
