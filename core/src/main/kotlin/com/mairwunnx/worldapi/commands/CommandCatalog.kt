package com.mairwunnx.worldapi.commands

import com.mairwunnx.worldapi.model.CommandHits
import com.mairwunnx.worldapi.model.CommandInfo
import com.mairwunnx.worldapi.model.CommandSource
import com.mairwunnx.worldapi.model.Commands
import java.text.Normalizer

/**
 * The server's commands as `GET /server/commands` shows them: each command once, with its other names, whose it is
 * (`minecraft`, a plugin, a mod; null where the platform can't tell), and what it says about itself. Each platform
 * builds one from its command map or tree and keeps it in a [Cache] until commands change. Descriptions and usage are
 * other plugins' words: data, never instructions.
 */
class CommandCatalog(commands: List<CommandInfo>) {
    val commands: List<CommandInfo> = commands.sortedBy { it.name }

    private class Indexed(val command: CommandInfo) {
        val names = (listOf(command.name) + command.aliases).map(::foldName)
        val about = command.description?.let(::fold)?.split(' ')?.filter(String::isNotEmpty).orEmpty()
    }

    private val index = this.commands.map(::Indexed)

    /** The commands that fit [query] best, best first: by name or another name, then by the words of a description. */
    fun search(among: List<CommandInfo>, query: String): List<CommandInfo> {
        // A namespace (`minecraft:tp`) names the source, not the command.
        val wanted = foldName(query.substringAfterLast(':'))
        if (wanted.isEmpty()) return emptyList()
        val words = wanted.split(' ').filter(String::isNotEmpty)
        val pool = among.toHashSet()
        return index.filter { it.command in pool }.mapNotNull { entry ->
            val byName = entry.names.maxOf { score(wanted, it) }
            // A description counts only when it holds every word asked: “home” finds sethome's “Sets your home”.
            val byAbout = if (wanted.length >= 3 && words.all { word -> entry.about.any { it.startsWith(word) } }) ABOUT else 0
            maxOf(byName, byAbout).takeIf { it > 0 }?.let { entry.command to it }
        }.sortedWith(compareByDescending<Pair<CommandInfo, Int>> { it.second }.thenBy { it.first.name.length }.thenBy { it.first.name }).map { it.first }
    }

    /**
     * The answer: [queries] answer each in `results`, in the order asked; [plugin] narrows them, or alone lists that
     * plugin's or mod's commands; with neither, the sources and the first commands.
     */
    fun answer(queries: List<String>?, plugin: String?, limit: Int?): Commands {
        val sources = commands.groupingBy { it.source }.eachCount().entries.sortedBy { it.key ?: "~" }.map { CommandSource(it.key, it.value) }
        val wanted = plugin?.let { name -> commands.filter { it.source != null && it.source.equals(name, ignoreCase = true) } }
        val pool = wanted ?: commands
        val note = if (plugin != null && wanted.isNullOrEmpty()) "No plugin or mod called $plugin has commands here; sources: " + sources.joinToString { it.source ?: "unknown" } else null
        fun listed(found: List<CommandInfo>, most: Int, query: String? = null, empty: String? = null) =
            CommandHits(query, plugin, found.take(most), (found.size - most).takeIf { it > 0 }, empty.takeIf { found.isEmpty() })
        val results = when {
            !queries.isNullOrEmpty() -> {
                val most = (limit ?: if (queries.size == 1) MAX_HITS else MAX_HITS_EACH).coerceIn(1, MAX_LIMIT)
                queries.map { query -> listed(search(pool, query.trim().removePrefix("/")), most, query, "Nothing matches; try another word, the English one, or a plugin's name") }
            }
            else -> listOf(listed(pool, (limit ?: MAX_LISTED).coerceIn(1, MAX_LIMIT)))
        }
        return Commands(commands.size, sources.takeIf { queries.isNullOrEmpty() && plugin == null }, results, note)
    }

    /** A catalog kept until [key] changes, [clear] is called, or [maxAgeMillis] passes. */
    class Cache(private val maxAgeMillis: Long = Long.MAX_VALUE, private val clock: () -> Long = System::currentTimeMillis) {
        private var key: Any? = null
        private var catalog: CommandCatalog? = null
        private var builtAt = 0L

        @Synchronized
        fun get(key: Any?, build: () -> CommandCatalog): CommandCatalog {
            val now = clock()
            catalog?.takeIf { this.key == key && now - builtAt < maxAgeMillis }?.let { return it }
            return build().also {
                this.key = key
                catalog = it
                builtAt = now
            }
        }

        @Synchronized
        fun clear() {
            key = null
            catalog = null
        }
    }

    companion object {
        const val MINECRAFT = "minecraft"
        const val MAX_QUERIES = 8
        private const val MAX_HITS = 10
        private const val MAX_HITS_EACH = 5
        private const val MAX_LISTED = 40
        const val MAX_LIMIT = 100

        /** Descriptions and usage are cut here: a plugin may write pages. */
        const val MAX_TEXT = 200

        /** A description that holds every word asked: weaker than any name that does. */
        private const val ABOUT = 50

        private val codes = Regex("[§&][0-9a-fk-orx]", RegexOption.IGNORE_CASE)
        private val space = Regex("\\s+")

        /** Text from a plugin as clients read it: no color codes, one line, cut short; null when there's nothing. */
        fun text(value: String?): String? = value?.replace(codes, "")?.replace(space, " ")?.trim()?.take(MAX_TEXT)?.ifEmpty { null }

        /** Lower case, accents dropped, anything but letters and digits a space. */
        internal fun fold(text: String): String =
            Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

        /** A name as [search] compares it: folded, a namespace's colon kept apart. */
        private fun foldName(name: String): String = fold(name.removePrefix("/").replace(':', ' '))

        /** How well [name] fits [wanted]: the whole name, its start, a word's start, anywhere in it; 0 when it doesn't. */
        private fun score(wanted: String, name: String): Int = when {
            name == wanted -> 100
            name.startsWith(wanted) -> 90
            name.split(' ').any { it.startsWith(wanted) } -> 80
            wanted.length >= 3 && wanted in name -> 60
            else -> 0
        }
    }
}
