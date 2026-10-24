package com.mairwunnx.worldapi.commands

import com.mairwunnx.worldapi.model.CommandInfo
import org.bukkit.Server
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.PluginIdentifiableCommand
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.server.PluginDisableEvent
import org.bukkit.event.server.PluginEnableEvent
import org.bukkit.event.server.ServerLoadEvent
import java.lang.reflect.Method

/**
 * The server's commands on Paper, from its command map: plugins' commands with their owner from
 * [PluginIdentifiableCommand], the game's and the server's own by the namespace they're registered under
 * (`minecraft:tp`, `bukkit:version`), and the commands plugins register through Brigadier, which Paper wraps as
 * commands of their plugin. Built on the server thread and kept until commands change: a plugin comes or goes, the
 * server loads, the map's size moves, and 5 minutes at most, since a plugin may register one whenever.
 * The same reading as Allay's `search_commands`, for the console, which may run every command.
 */
class PaperCommands(private val server: Server) : Listener {
    private val cache = CommandCatalog.Cache(MAX_AGE_MILLIS)

    @EventHandler(priority = EventPriority.MONITOR)
    fun onEnable(event: PluginEnableEvent) = cache.clear()

    @EventHandler(priority = EventPriority.MONITOR)
    fun onDisable(event: PluginDisableEvent) = cache.clear()

    @EventHandler(priority = EventPriority.MONITOR)
    fun onLoad(event: ServerLoadEvent) = cache.clear()

    /** Server thread only: Paper builds the map's view of its Brigadier tree on each read. */
    fun catalog(): CommandCatalog {
        val known = server.commandMap.knownCommands
        return cache.get(known.size) { build(known) }
    }

    /**
     * A game command's Brigadier node: CraftBukkit's `VanillaCommandWrapper` holds it, and so do Paper's wrappers of
     * plugins' Brigadier commands. Paper hands the game's own nodes out as a `ShadowBrigNode`; the game's node is its
     * handle.
     */
    private fun node(command: Command): Any? {
        var type: Class<*>? = command.javaClass
        while (type != null && type != Command::class.java) {
            if (type.simpleName == "VanillaCommandWrapper") {
                wrapper = type
                return runCatching { type.getDeclaredField("vanillaCommand").also { it.isAccessible = true }.get(command) }.getOrNull()?.let(::handle)?.let(::target)
            }
            type = type.superclass
        }
        return null
    }

    private fun handle(node: Any): Any =
        if (node.javaClass.simpleName == "ShadowBrigNode") runCatching { node.javaClass.getMethod("getHandle").invoke(node) }.getOrNull() ?: node else node

    /** Where a node that only redirects (`tp` to `teleport`) leads; never the root, which `execute run` leads to. */
    private fun target(node: Any): Any {
        var at = node
        repeat(8) {
            val next = runCatching { at.javaClass.getMethod("getRedirect").invoke(at) }.getOrNull() ?: return at
            if (next.javaClass.simpleName == "RootCommandNode") return at
            at = next
        }
        return at
    }

    /**
     * What makes two names one command: the same object, or for a Brigadier node the same name without its namespace
     * and the same code. Paper copies a node for `minecraft:give` and wraps each anew, and gives every game command the
     * same code under its wrapper, so neither the node nor the code alone tells.
     */
    private class Same(val name: String, val ref: Any) {
        override fun equals(other: Any?) = other is Same && other.name == name && other.ref === ref
        override fun hashCode() = name.hashCode() * 31 + System.identityHashCode(ref)
    }

    private fun same(command: Command): Same {
        val node = node(command) ?: return Same("", command)
        fun call(name: String) = runCatching { node.javaClass.getMethod(name).invoke(node) }.getOrNull()
        val code = call("getCommand") ?: (call("getChildren") as? Collection<*>)?.firstOrNull() ?: node
        return Same((call("getName") as? String ?: command.name).lowercase().substringAfter(':'), code)
    }

    @Volatile private var wrapper: Class<*>? = null
    private var getListener: Method? = null

    /** The game's source for a sender, which Brigadier's usage reads: `VanillaCommandWrapper.getListener`. */
    private fun listener(sender: CommandSender): Any? = runCatching {
        val type = wrapper ?: return null
        (getListener ?: type.getMethod("getListener", CommandSender::class.java).also { getListener = it }).invoke(null, sender)
    }.getOrNull()

    /**
     * One entry per command: Paper hands out a new wrapper for a Brigadier node on each read, so commands group by
     * [same]. Its name is the one typed without a namespace, unless another plugin took it; the namespace says whose
     * it is where the command doesn't.
     */
    private fun build(known: Map<String, Command>): CommandCatalog {
        val groups = LinkedHashMap<Any, MutableList<Pair<String, Command>>>()
        for ((label, command) in known) groups.getOrPut(same(command)) { mutableListOf() } += label.lowercase() to command
        class Found(val info: CommandInfo, val command: Command, val node: Any?)
        val found = groups.values.mapNotNull { labels ->
            val command = labels.first().second
            val plain = labels.map { it.first }.filter { ':' !in it }.distinct()
            val namespaced = labels.map { it.first }.filter { ':' in it }
            val own = command.name.lowercase()
            val name = own.takeIf { it in plain } ?: plain.firstOrNull() ?: namespaced.firstOrNull() ?: return@mapNotNull null
            val source = (command as? PluginIdentifiableCommand)?.plugin?.name ?: namespaced.firstOrNull()?.substringBefore(':')
            val description = command.description.takeUnless { it == MOJANG }?.let(CommandCatalog::text)
            val node = node(command)
            // A Brigadier command's usage comes from its tree below; Paper's wrapper only repeats a name.
            val usage = if (node != null) null else command.usage.replace("<command>", name).let(CommandCatalog::text)?.takeUnless { it == "/$name" || it == name }
            Found(CommandInfo(name, (plain - name).sorted(), source, description, usage), command, node)
        }
        val merged = merged(found.map { Triple(it.info, it.command, it.node) })
        val usages = usages(merged.mapNotNull { it.third })
        return CommandCatalog(merged.map { (info, _, node) ->
            val usage = node?.let { usages[it] }?.let { "/${info.name}" + it.substringAfter(' ', "").let { rest -> if (rest.isEmpty()) "" else " $rest" } }
            if (usage != null) info.copy(usage = CommandCatalog.text(usage)) else info
        })
    }

    /**
     * Paper registers each alias of a plugin's Brigadier command as a node of its own; the command still names its
     * aliases, so they join it as on Bukkit.
     */
    private fun merged(entries: List<Triple<CommandInfo, Command, Any?>>): List<Triple<CommandInfo, Command, Any?>> {
        val byName = entries.associateBy { it.first.name }
        val folded = HashMap<Triple<CommandInfo, Command, Any?>, MutableList<String>>()
        val gone = HashSet<Triple<CommandInfo, Command, Any?>>()
        for (entry in entries) {
            if (entry in gone || entry.third == null) continue
            for (alias in entry.second.aliases.map(String::lowercase)) {
                val other = byName[alias]?.takeIf { it !== entry && it !in gone && it.first.source == entry.first.source && it.third != null } ?: continue
                gone += other
                folded.getOrPut(entry) { mutableListOf() } += listOf(other.first.name) + other.first.aliases
            }
        }
        return entries.filter { it !in gone }.map { entry ->
            val more = folded[entry] ?: return@map entry
            entry.copy(first = entry.first.copy(aliases = (entry.first.aliases + more).distinct().sorted()))
        }
    }

    /**
     * Brigadier commands' usage as the console may use them, as the game's `/help` shows it. Worked out on a
     * dispatcher of its own, with one node per name, or adding them would merge them into the real tree.
     */
    @Suppress("UNCHECKED_CAST")
    private fun usages(nodes: List<Any>): Map<Any, String> = runCatching {
        val shown = nodes.distinctBy { it.javaClass.getMethod("getName").invoke(it) }
        if (shown.isEmpty()) return emptyMap()
        val loader = shown.first().javaClass.classLoader
        val dispatcherType = Class.forName("com.mojang.brigadier.CommandDispatcher", false, loader)
        val nodeType = Class.forName("com.mojang.brigadier.tree.CommandNode", false, loader)
        val dispatcher = dispatcherType.getConstructor().newInstance()
        val root = dispatcherType.getMethod("getRoot").invoke(dispatcher)
        val add = nodeType.getMethod("addChild", nodeType)
        shown.forEach { add.invoke(root, it) }
        val source = listener(server.consoleSender) ?: return emptyMap()
        val smart = dispatcherType.getMethod("getSmartUsage", nodeType, Any::class.java).invoke(dispatcher, root, source) as Map<Any, String>
        val byName = shown.associateBy { it.javaClass.getMethod("getName").invoke(it) }
        // Nodes of the same name share one usage: the one the dispatcher kept.
        nodes.mapNotNull { node -> (smart[node] ?: smart[byName[node.javaClass.getMethod("getName").invoke(node)]])?.let { node to "/$it" } }.toMap()
    }.getOrDefault(emptyMap())

    private companion object {
        const val MAX_AGE_MILLIS = 5 * 60_000L

        /** What the game's own commands say about themselves on Bukkit: nothing. */
        const val MOJANG = "A Mojang provided command."
    }
}
