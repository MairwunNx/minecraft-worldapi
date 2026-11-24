package com.mairwunnx.worldapi.store

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.text.Normalizer

/**
 * Words to find in events' values, never their keys: any of them, or with [all] every one. Case, accents, ё, and
 * Minecraft's § colors don't matter, in any language.
 */
class TextMatch(terms: List<String>, private val all: Boolean = false) {
    private val needles = terms.map { fold(it).trim() }.filter { it.isNotEmpty() }.distinct()

    val empty get() = needles.isEmpty()

    /** How often the words appear in [data], or null when it doesn't match. */
    fun hits(data: JsonObject): Int? {
        val text = fold(buildString { collect(data) })
        val counts = needles.map { occurrences(text, it) }
        val matches = if (all) counts.all { it > 0 } else counts.any { it > 0 }
        return if (matches) counts.sum() else null
    }

    private fun StringBuilder.collect(value: JsonElement) {
        when (value) {
            is JsonObject -> value.values.forEach { collect(it) }
            is JsonArray -> value.forEach { collect(it) }
            is JsonPrimitive -> append(value.content).append('\n')
        }
    }

    private fun occurrences(text: String, needle: String): Int {
        var count = 0
        var at = text.indexOf(needle)
        while (at >= 0) {
            count++
            at = text.indexOf(needle, at + needle.length)
        }
        return count
    }

    companion object {
        private val COLORS = Regex("§[0-9a-fk-orx]", RegexOption.IGNORE_CASE)
        private val MARKS = Regex("\\p{M}+")

        /** Lower case without colors and accents: ё reads as е, é as e. */
        fun fold(text: String): String = MARKS.replace(Normalizer.normalize(COLORS.replace(text, "").lowercase(), Normalizer.Form.NFD), "")
    }
}
