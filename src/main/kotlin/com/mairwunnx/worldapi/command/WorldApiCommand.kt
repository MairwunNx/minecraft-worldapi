package com.mairwunnx.worldapi.command

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.tree.LiteralCommandNode
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.entity.Player

/** What the command can do with tokens; the plugin supplies it. */
interface TokenCommands {
    /** Opens the creation menu, or says why it can't. */
    fun create(player: Player, name: String): String?
    fun list(): List<String>
    fun revoke(name: String): Result<String>
    fun issuedNames(): List<String>

    /** Shows the player a code for the admin panel, or says why not. */
    fun adminCode(player: Player, group: String?): String?
    fun groupNames(): List<String>
}

/** `/worldapi reload | status | admin [group] | token create|list|revoke`, for operators with worldapi.admin. */
object WorldApiCommand {
    fun build(reload: () -> Result<String>, status: () -> List<String>, tokens: TokenCommands): LiteralCommandNode<CommandSourceStack> =
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
            .then(
                Commands.literal("admin")
                    .executes { context -> admin(context.source.sender, tokens, null) }
                    .then(Commands.argument("group", StringArgumentType.word())
                        .suggests { _, builder ->
                            tokens.groupNames().filter { it.startsWith(builder.remainingLowerCase) }.forEach(builder::suggest)
                            builder.buildFuture()
                        }
                        .executes { context -> admin(context.source.sender, tokens, StringArgumentType.getString(context, "group")) }),
            )
            .then(
                Commands.literal("token")
                    .then(Commands.literal("create").then(Commands.argument("name", StringArgumentType.word()).executes { context ->
                        val sender = context.source.sender
                        // The token is shown once, in chat; the console would write it to the log.
                        if (sender !is Player) {
                            sender.sendMessage(Component.text("Create tokens in game: the console would write the token to the log.", NamedTextColor.RED))
                        } else {
                            tokens.create(sender, StringArgumentType.getString(context, "name"))?.let { sender.sendMessage(Component.text(it, NamedTextColor.RED)) }
                        }
                        Command.SINGLE_SUCCESS
                    }))
                    .then(Commands.literal("list").executes { context ->
                        tokens.list().forEach { context.source.sender.sendMessage(Component.text(it)) }
                        Command.SINGLE_SUCCESS
                    })
                    .then(Commands.literal("revoke").then(
                        Commands.argument("name", StringArgumentType.word())
                            .suggests { _, builder ->
                                tokens.issuedNames().filter { it.startsWith(builder.remainingLowerCase) }.forEach(builder::suggest)
                                builder.buildFuture()
                            }
                            .executes { context ->
                                val sender = context.source.sender
                                tokens.revoke(StringArgumentType.getString(context, "name")).fold(
                                    { sender.sendMessage(Component.text(it, NamedTextColor.GREEN)) },
                                    { sender.sendMessage(Component.text(it.message ?: "Couldn't revoke", NamedTextColor.RED)) },
                                )
                                Command.SINGLE_SUCCESS
                            },
                    )),
            )
            .build()

    private fun admin(sender: org.bukkit.command.CommandSender, tokens: TokenCommands, group: String?): Int {
        // Like a token, the code goes to one player's chat; the console would log it.
        if (sender !is Player) {
            sender.sendMessage(Component.text("Get a panel code in game: the console would write it to the log.", NamedTextColor.RED))
        } else {
            tokens.adminCode(sender, group)?.let { sender.sendMessage(Component.text(it, NamedTextColor.RED)) }
        }
        return Command.SINGLE_SUCCESS
    }
}
