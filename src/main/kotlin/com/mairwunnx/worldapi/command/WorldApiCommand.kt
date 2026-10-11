package com.mairwunnx.worldapi.command

import com.mojang.brigadier.Command
import com.mojang.brigadier.tree.LiteralCommandNode
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor

/** `/worldapi reload` and `/worldapi status`, for operators with worldapi.admin. */
object WorldApiCommand {
    fun build(reload: () -> Result<String>, status: () -> List<String>): LiteralCommandNode<CommandSourceStack> =
        Commands.literal("worldapi")
            .requires { it.sender.hasPermission("worldapi.admin") }
            .then(Commands.literal("reload").executes { context ->
                val sender = context.source.sender
                reload().fold(
                    { sender.sendMessage(Component.text(it, NamedTextColor.GREEN)) },
                    { sender.sendMessage(Component.text("WorldAPI kept the old config: ${it.message}", NamedTextColor.RED)) },
                )
                Command.SINGLE_SUCCESS
            })
            .then(Commands.literal("status").executes { context ->
                status().forEach { context.source.sender.sendMessage(Component.text(it)) }
                Command.SINGLE_SUCCESS
            })
            .build()
}
