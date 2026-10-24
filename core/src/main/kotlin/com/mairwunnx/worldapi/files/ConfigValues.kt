package com.mairwunnx.worldapi.files

import com.mairwunnx.worldapi.api.badRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Sets values in a config by dotted path, keeping what a person wrote around them: YAML through the
 * platform's [Yaml] with comments kept, `.properties` line by line, JSON as a tree. A null value
 * removes the key.
 */
object ConfigValues {
    private val pretty = Json { prettyPrint = true; prettyPrintIndent = "  " }

    /** Sets dotted keys in a YAML document, comments kept; each platform has its own. */
    fun interface Yaml {
        fun set(text: String, values: Map<String, JsonElement>): String
    }

    fun set(fileName: String, text: String, values: Map<String, JsonElement>, yaml: Yaml): String {
        if (values.isEmpty()) badRequest("values is empty")
        values.keys.forEach { if (it.isBlank() || it.startsWith('.') || it.endsWith('.') || ".." in it) badRequest("\"$it\" isn't a dotted path") }
        return when (fileName.substringAfterLast('.').lowercase()) {
            "yml", "yaml" -> yaml.set(text, values)
            "properties" -> properties(text, values)
            "json" -> json(text, values)
            else -> badRequest("Setting values works for YAML, .properties, and JSON; edit $fileName with /files/edit", "unsupported_format")
        }
    }

    /** Replaces `key=value` lines in place and appends new keys, so comments and order stay. */
    private fun properties(text: String, values: Map<String, JsonElement>): String {
        val lines = text.lines().toMutableList()
        if (lines.lastOrNull() == "") lines.removeAt(lines.lastIndex)
        values.forEach { (key, value) ->
            if (value is JsonObject || value is JsonArray) badRequest("$key: .properties values are plain text, numbers, or booleans")
            val index = lines.indexOfFirst { line ->
                val trimmed = line.trimStart()
                !trimmed.startsWith("#") && !trimmed.startsWith("!") && trimmed.split('=', ':', limit = 2).first().trim() == key
            }
            val plain = (value as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
            when {
                plain == null && index >= 0 -> lines.removeAt(index)
                plain == null -> {}
                index >= 0 -> lines[index] = "$key=${escape(plain)}"
                else -> lines += "$key=${escape(plain)}"
            }
        }
        return lines.joinToString("\n", postfix = "\n")
    }

    private fun escape(value: String) = value.replace("\\", "\\\\").replace("\n", "\\n")

    private fun json(text: String, values: Map<String, JsonElement>): String {
        val root = runCatching { Json.parseToJsonElement(text.ifBlank { "{}" }) }.getOrElse { badRequest("The file isn't valid JSON: ${it.message}", "invalid_file") }
        if (root !is JsonObject) badRequest("Setting values needs a JSON object at the top; edit the file with /files/edit")
        var result: JsonObject = root
        values.forEach { (path, value) -> result = put(result, path.split('.'), value) }
        return pretty.encodeToString(JsonObject.serializer(), result) + "\n"
    }

    private fun put(node: JsonObject, path: List<String>, value: JsonElement): JsonObject {
        val key = path.first()
        val map = LinkedHashMap(node)
        if (path.size == 1) {
            if (value is JsonNull) map.remove(key) else map[key] = value
        } else {
            val child = map[key] as? JsonObject ?: JsonObject(emptyMap())
            map[key] = put(child, path.drop(1), value)
        }
        return JsonObject(map)
    }


    /** Applies exact text replacements in order; each must match once unless [Edit.replaceAll]. */
    fun edit(text: String, edits: List<Edit>): String {
        if (edits.isEmpty()) badRequest("edits is empty")
        // /files/read shows lines without their \r, so edits come with \n; a CRLF file gets them back.
        if ("\r\n" in text) {
            return edit(text.replace("\r\n", "\n"), edits.map { it.copy(oldText = it.oldText.replace("\r\n", "\n"), newText = it.newText.replace("\r\n", "\n")) }).replace("\n", "\r\n")
        }
        var result = text
        edits.forEachIndexed { index, edit ->
            if (edit.oldText.isEmpty()) badRequest("edits[$index].oldText is empty; to write a whole file use PUT /files/write")
            if (edit.oldText == edit.newText) badRequest("edits[$index] changes nothing")
            val count = occurrences(result, edit.oldText)
            when {
                count == 0 -> badRequest("edits[$index].oldText isn't in the file; read it again and copy the text exactly, spaces included", "edit_not_found")
                count > 1 && !edit.replaceAll -> badRequest("edits[$index].oldText appears $count times; add lines around it to make it unique, or set replaceAll", "edit_not_unique")
            }
            result = if (edit.replaceAll) result.replace(edit.oldText, edit.newText) else result.replaceFirst(edit.oldText, edit.newText)
        }
        return result
    }

    private fun occurrences(text: String, part: String): Int {
        var count = 0
        var from = text.indexOf(part)
        while (from >= 0) {
            count++
            from = text.indexOf(part, from + part.length)
        }
        return count
    }

    data class Edit(val oldText: String, val newText: String, val replaceAll: Boolean)
}
