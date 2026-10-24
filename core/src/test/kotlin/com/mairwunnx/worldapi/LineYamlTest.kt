package com.mairwunnx.worldapi

import com.mairwunnx.worldapi.api.ApiException
import com.mairwunnx.worldapi.files.LineYaml
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LineYamlTest {
    private val text = """
        # The mod's settings
        general:
          # How often, in ticks
          interval: 20 # keep it low
          name: "old"
          list:
            - a
            - b
        other: true
    """.trimIndent() + "\n"

    @Test
    fun `a value changes in place and keeps its comment`() {
        val result = LineYaml.set(text, mapOf("general.interval" to JsonPrimitive(40)))
        assertEquals(text.replace("interval: 20 # keep it low", "interval: 40 # keep it low"), result)
    }

    @Test
    fun `strings are quoted and lists become flow lists`() {
        val result = LineYaml.set(text, mapOf("general.name" to JsonPrimitive("new \"one\""), "general.list" to JsonArray(listOf(JsonPrimitive("c")))))
        assertEquals(
            text.replace("name: \"old\"", "name: \"new \\\"one\\\"\"").replace("  list:\n    - a\n    - b\n", "  list: [\"c\"]\n"),
            result,
        )
    }

    @Test
    fun `new keys go at the end of their section, with the sections on the way`() {
        val result = LineYaml.set(text, mapOf("general.deep.key" to JsonPrimitive(true), "top" to JsonPrimitive(1)))
        assertEquals(text.replace("    - b\n", "    - b\n  deep:\n    key: true\n") + "top: 1\n", result)
    }

    @Test
    fun `null removes a key and what sits under it`() {
        val result = LineYaml.set(text, mapOf("general.list" to JsonNull, "other" to JsonNull))
        assertEquals(text.replace("  list:\n    - a\n    - b\n", "").replace("other: true\n", ""), result)
    }

    @Test
    fun `invalid YAML is refused`() {
        assertFailsWith<ApiException> { LineYaml.set("a: [unclosed\n", mapOf("a" to JsonPrimitive(1))) }
        assertFailsWith<ApiException> { LineYaml.set("a: 1\n", mapOf("a.b" to JsonPrimitive(1))) }
    }
}
