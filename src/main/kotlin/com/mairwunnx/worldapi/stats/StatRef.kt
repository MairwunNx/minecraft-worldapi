package com.mairwunnx.worldapi.stats

/** Statistics as vanilla stores them: category, such as `minecraft:mined`, then key, then value. */
typealias StatMap = Map<String, Map<String, Long>>

/**
 * One statistic, written `category:key` with the namespaces left out: `custom:play_time`,
 * `mined:diamond_ore`, `killed:zombie`. A key of `*` adds up the whole category.
 */
data class StatRef(val category: String, val key: String) {
    val wildcard get() = key == "*"

    fun read(stats: StatMap): Long {
        val values = stats[category] ?: return 0
        return if (wildcard) values.values.sum() else values[key] ?: 0
    }

    override fun toString() = "${category.removePrefix("minecraft:")}:${if (wildcard) "*" else key.removePrefix("minecraft:")}"

    companion object {
        val CATEGORIES = listOf("custom", "mined", "broken", "crafted", "used", "picked_up", "dropped", "killed", "killed_by")

        /** Parses `custom:play_time` or `minecraft:custom/minecraft:play_time`; null if it isn't a statistic. */
        fun parse(text: String): StatRef? {
            val parts = text.lowercase().split('/').takeIf { it.size == 2 } ?: text.lowercase().split(':', limit = 2)
            if (parts.size != 2) return null
            val category = parts[0].removePrefix("minecraft:")
            if (category !in CATEGORIES) return null
            val key = parts[1].removePrefix("minecraft:")
            if (key.isEmpty() || !(key == "*" || Regex("[a-z0-9_./-]+").matches(key))) return null
            return StatRef("minecraft:$category", if (key == "*") "*" else namespaced(key))
        }

        private fun namespaced(key: String) = if (':' in key) key else "minecraft:$key"
    }
}
