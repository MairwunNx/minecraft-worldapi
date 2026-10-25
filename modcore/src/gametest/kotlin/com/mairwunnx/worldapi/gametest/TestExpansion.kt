package com.mairwunnx.worldapi.gametest

import com.mairwunnx.worldapi.expansion.ExpansionApi
import com.mairwunnx.worldapi.expansion.Resource
import com.mairwunnx.worldapi.expansion.WorldApiExpansion

/** An expansion another mod would ship, named in the test mod's META-INF/services, as the Paper smoke plugin's is. */
class TestExpansion : WorldApiExpansion {
    override fun id() = "test_found"

    override fun requiresApi(): String = ExpansionApi.VERSION

    override fun description() = "The game tests' expansion"

    override fun resources(): List<Resource> = listOf(
        Resource.of("echo/{word}", "The word and who asks") { request -> mapOf("word" to request.path("word"), "anonymous" to request.anonymous()) }.publicly(),
    )
}
