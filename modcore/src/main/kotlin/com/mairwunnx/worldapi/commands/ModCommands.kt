package com.mairwunnx.worldapi.commands

import com.mairwunnx.worldapi.model.CommandInfo
import com.mairwunnx.worldapi.modcore.Loader
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.tree.CommandNode
import com.mojang.brigadier.tree.RootCommandNode
import net.minecraft.commands.CommandSourceStack
import net.minecraft.server.MinecraftServer

/**
 * The server's commands on the mods, from the root of its Brigadier tree, as Allay's mods read them. Brigadier keeps no
 * owner, so a command's comes from a namespace it's also registered under (`mymod:home`), else from the class its code
 * lives in, which the loader maps to a mod ([Loader.owner]); the game's own are `minecraft`. A node that only
 * redirects (`tp` to `teleport`) is the other's alias. Brigadier has no descriptions, so the catalog has none; usage
 * comes from the tree as the console may use it, as `/help` shows it. Kept until the tree changes: a `/reload` builds
 * a new one, and a mod may add a root node any time, which moves the count.
 */
class ModCommands(private val server: MinecraftServer, private val loader: Loader) {
    private val cache = CommandCatalog.Cache(MAX_AGE_MILLIS)

    /** Server thread only. */
    fun catalog(): CommandCatalog {
        val dispatcher = server.commands.dispatcher
        return cache.get(listOf(System.identityHashCode(dispatcher), dispatcher.root.children.size)) { build(dispatcher) }
    }

    /** Where a node that only redirects leads; never the root, which `execute run` leads to. */
    private fun target(node: CommandNode<CommandSourceStack>): CommandNode<CommandSourceStack> {
        var at = node
        repeat(8) {
            val next = at.redirect ?: return at
            if (next is RootCommandNode || at.command != null || at.children.isNotEmpty()) return at
            at = next
        }
        return at
    }

    /** The first code under [node], whose class says whose command it is. */
    private fun code(node: CommandNode<CommandSourceStack>, depth: Int = 0): Class<*>? {
        node.command?.let { return it.javaClass }
        if (depth >= DEPTH) return null
        return node.children.firstNotNullOfOrNull { code(it, depth + 1) }
    }

    private fun owner(node: CommandNode<CommandSourceStack>): String? {
        val type = code(node) ?: return null
        if (type.name.startsWith("net.minecraft.")) return CommandCatalog.MINECRAFT
        return runCatching { loader.owner(type) }.getOrNull()
    }

    private fun build(dispatcher: CommandDispatcher<CommandSourceStack>): CommandCatalog {
        val groups = LinkedHashMap<CommandNode<CommandSourceStack>, MutableList<String>>()
        for (child in dispatcher.root.children) groups.getOrPut(target(child)) { mutableListOf() } += child.name.lowercase()
        val entries = groups.map { (node, labels) ->
            val plain = labels.filter { ':' !in it }
            val namespaced = labels.filter { ':' in it }
            val own = node.name.lowercase()
            val name = own.takeIf { it in plain } ?: plain.firstOrNull() ?: own
            val source = namespaced.firstOrNull()?.substringBefore(':') ?: owner(node)
            node to CommandInfo(name, (plain - name).sorted(), source, null, null)
        }
        val usages = usages(dispatcher, entries.map { it.first })
        return CommandCatalog(entries.map { (node, info) ->
            val usage = usages[node]?.let { "/${info.name}" + it.substringAfter(' ', "").let { rest -> if (rest.isEmpty()) "" else " $rest" } }
            if (usage != null) info.copy(usage = CommandCatalog.text(usage)) else info
        })
    }

    /** Usage as the console may use each command; a root of its own, so adding the nodes leaves the real tree alone. */
    private fun usages(dispatcher: CommandDispatcher<CommandSourceStack>, nodes: List<CommandNode<CommandSourceStack>>): Map<CommandNode<CommandSourceStack>, String> {
        val root = RootCommandNode<CommandSourceStack>()
        val shown = nodes.distinctBy { it.name }
        shown.forEach { root.addChild(it) }
        val smart = runCatching { dispatcher.getSmartUsage(root, server.createCommandSourceStack()) }.getOrDefault(emptyMap())
        val byName = shown.associateBy { it.name }
        return nodes.mapNotNull { node -> (smart[node] ?: smart[byName[node.name]])?.let { node to "/$it" } }.toMap()
    }

    private companion object {
        const val MAX_AGE_MILLIS = 5 * 60_000L

        /** How deep to look for a node's code: `home` runs nothing itself, `home <name>` does. */
        const val DEPTH = 4
    }
}
