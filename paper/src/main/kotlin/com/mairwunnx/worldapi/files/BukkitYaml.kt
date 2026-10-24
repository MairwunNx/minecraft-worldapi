package com.mairwunnx.worldapi.files

import com.mairwunnx.worldapi.api.badRequest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import org.bukkit.configuration.file.YamlConfiguration

/** YAML keys through Bukkit's own YamlConfiguration, with comments kept. */
object BukkitYaml : ConfigValues.Yaml {
    override fun set(text: String, values: Map<String, JsonElement>): String {
        val config = YamlConfiguration()
        config.options().parseComments(true)
        runCatching { config.loadFromString(text) }.onFailure { badRequest("The file isn't valid YAML: ${it.message}", "invalid_file") }
        values.forEach { (path, value) -> config.set(path, java(value)) }
        return config.saveToString()
    }

    /** JSON to what YamlConfiguration stores: whole numbers stay integers, objects become sections. */
    private fun java(value: JsonElement): Any? = when (value) {
        is JsonNull -> null
        is JsonPrimitive -> when {
            value.isString -> value.content
            value.booleanOrNull != null -> value.booleanOrNull
            value.longOrNull != null -> value.longOrNull!!.let { if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else it }
            else -> value.doubleOrNull
        }
        is JsonArray -> value.map(::java)
        is JsonObject -> value.mapValues { java(it.value) }
    }
}
