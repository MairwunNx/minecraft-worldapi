package com.mairwunnx.worldapi.gametest

import com.mairwunnx.worldapi.expansion.v2.Expansion

/** An expansion another mod would ship: an object named in the test mod's META-INF/services, as the Paper smoke plugin's is. */
object TestExpansion : Expansion("test_found", "WorldAPI game tests", "The game tests’ expansion") {
    val word = text("word", "Any word")

    val echo = resource("echo/{word}", "The word and who asks") { req -> mapOf("word" to req[word], "anonymous" to req.anonymous()) }.publicly()
}
