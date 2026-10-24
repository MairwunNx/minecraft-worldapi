package com.mairwunnx.worldapi.files

import com.charleskorn.kaml.Yaml
import com.mairwunnx.worldapi.api.badRequest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * YAML keys set line by line, for platforms without Bukkit's YamlConfiguration: a key's line changes in place, its
 * comment and everything around it stay, a new key goes at the end of its section, and null removes a key with what
 * sits under it. Values go in as flow YAML (JSON is valid YAML), so a list or a map stays one line. Both the file and
 * the result must parse, or the change is refused.
 */
object LineYaml : ConfigValues.Yaml {
    private class Entry(val path: List<String>, val indent: Int, val line: Int, var end: Int)

    override fun set(text: String, values: Map<String, JsonElement>): String {
        check(text)
        var lines = text.lines().toMutableList()
        val trailing = lines.lastOrNull() == ""
        if (trailing) lines.removeAt(lines.lastIndex)
        values.forEach { (path, value) -> lines = apply(lines, path.split('.'), value) }
        val result = lines.joinToString("\n") + "\n"
        check(result)
        return result
    }

    private fun check(text: String) {
        if (text.isBlank()) return
        runCatching { Yaml.default.parseToYamlNode(text) }.onFailure { badRequest("The file isn't valid YAML: ${it.message}", "invalid_file") }
    }

    private fun apply(lines: MutableList<String>, path: List<String>, value: JsonElement): MutableList<String> {
        val entries = entries(lines)
        val found = entries.firstOrNull { it.path == path }
        if (found != null) {
            val head = lines[found.line]
            val key = head.substring(0, head.indexOf(':', keyStart(head)) + 1)
            // A key that holds a section or a list loses it: the new value replaces everything under the key.
            repeat(found.end - found.line) { lines.removeAt(found.line + 1) }
            if (value is JsonNull) lines.removeAt(found.line) else lines[found.line] = "$key ${render(value)}" + comment(head)
            return lines
        }
        if (value is JsonNull) return lines
        // The deepest section that exists takes the rest of the path, one nested line per missing key.
        val parent = (path.size - 1 downTo 1).firstNotNullOfOrNull { size -> entries.firstOrNull { it.path == path.take(size) } }
        val depth = parent?.path?.size ?: 0
        val indent = if (parent == null) 0 else childIndent(lines, parent)
        val added = path.drop(depth).mapIndexed { i, key ->
            val pad = " ".repeat(indent + i * 2)
            if (i == path.size - depth - 1) "$pad${quoteKey(key)}: ${render(value)}" else "$pad${quoteKey(key)}:"
        }
        if (parent != null && lines[parent.line].substringAfter(':').substringBefore(" #").isNotBlank()) {
            badRequest("${parent.path.joinToString(".")} holds a value, not a section", "invalid_path")
        }
        val at = parent?.end?.plus(1) ?: lines.indexOfLast { it.isNotBlank() }.plus(1)
        lines.addAll(at, added)
        return lines
    }

    /** Every `key:` line with its dotted path and the last line that belongs to it. */
    private fun entries(lines: List<String>): List<Entry> {
        val result = mutableListOf<Entry>()
        val stack = ArrayDeque<Entry>()
        lines.forEachIndexed { index, line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed == "---") return@forEachIndexed
            val indent = line.length - line.trimStart().length
            val listItem = trimmed.startsWith("- ") || trimmed == "-"
            while (stack.isNotEmpty() && (stack.last().indent > indent || (stack.last().indent == indent && !listItem))) stack.removeLast()
            stack.forEach { it.end = index }
            if (listItem) return@forEachIndexed
            val key = key(line) ?: return@forEachIndexed
            val entry = Entry(stack.map { it.path.last() } + key, indent, index, index)
            result += entry
            stack.addLast(entry)
        }
        return result
    }

    private fun keyStart(line: String) = line.length - line.trimStart().length

    /** The key of a `key: value` or `key:` line, without quotes; null for anything else. */
    private fun key(line: String): String? {
        val body = line.trimStart()
        val raw = when {
            body.startsWith("\"") -> body.substring(1).substringBefore('"').also { if (!body.substring(it.length + 2).startsWith(":")) return null }
            body.startsWith("'") -> body.substring(1).substringBefore('\'').also { if (!body.substring(it.length + 2).startsWith(":")) return null }
            else -> Regex("""^([^:#\s][^:#]*?)\s*:(\s|$)""").find(body)?.groupValues?.get(1) ?: return null
        }
        return raw
    }

    private fun childIndent(lines: List<String>, parent: Entry): Int {
        val child = (parent.line + 1..parent.end).map(lines::get).firstOrNull { it.isNotBlank() && !it.trim().startsWith("#") }
        return child?.let { it.length - it.trimStart().length }?.takeIf { it > parent.indent } ?: (parent.indent + 2)
    }

    /** A trailing `# comment` of a value line, kept when the value changes. */
    private fun comment(line: String): String {
        val value = line.substring(line.indexOf(':', keyStart(line)) + 1)
        var quote: Char? = null
        value.forEachIndexed { i, c ->
            when {
                quote != null -> if (c == quote) quote = null
                c == '"' || c == '\'' -> quote = c
                c == '#' && i > 0 && value[i - 1] == ' ' -> return " " + value.substring(i)
            }
        }
        return ""
    }

    private fun quoteKey(key: String) = if (Regex("^[A-Za-z0-9_-]+$").matches(key)) key else "\"" + key.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun render(value: JsonElement): String = when (value) {
        is JsonPrimitive -> if (value.isString) "\"" + value.content.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"" else value.content
        is JsonArray -> value.joinToString(", ", "[", "]", transform = ::render)
        is JsonObject -> value.entries.joinToString(", ", "{", "}") { (k, v) -> "${quoteKey(k)}: ${render(v)}" }
        JsonNull -> "null"
    }
}
