package com.mairwunnx.worldapi.modcore

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.MutableComponent
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.permissions.Permissions
import java.time.Instant

/**
 * `/worldapi [status | reload | connect | admin [group] | token create <name> [group] | list | revoke <name>]` on the
 * mods, for operators with `worldapi.admin` and for the owner of a singleplayer world, cheats or not. Paper's opens
 * chest menus; here everything answers in chat. A token and a panel code go to one player's chat only: the console
 * would write them to the log.
 */
object WorldApiCommand {
    const val PERMISSION = "worldapi.admin"
    private val NAME = Regex("[A-Za-z0-9_-]{1,32}")

    fun build(): LiteralArgumentBuilder<CommandSourceStack> = Commands.literal("worldapi")
        .requires(::allowed)
        .executes { run(it) { source, runtime -> runtime.status().forEach { line -> source.sendSystemMessage(Component.literal(line)) } } }
        .then(Commands.literal("status").executes { run(it) { source, runtime -> runtime.status().forEach { line -> source.sendSystemMessage(Component.literal(line)) } } })
        .then(Commands.literal("reload").executes {
            run(it) { source, runtime ->
                runtime.reload().fold(
                    { message -> source.sendSystemMessage(Component.literal(message).withStyle(ChatFormatting.GREEN)) },
                    { error -> source.sendFailure(Component.literal("WorldAPI kept the old config: ${error.message}")) },
                )
            }
        })
        .then(Commands.literal("connect").executes { run(it) { source, runtime -> connect(source, runtime) } })
        .then(
            Commands.literal("admin")
                .executes { run(it) { source, runtime -> admin(source, runtime, null) } }
                .then(Commands.argument("group", StringArgumentType.word())
                    .suggests { context, builder ->
                        Mod.runtime?.config?.get()?.auth?.groups?.keys?.filter { name -> name.startsWith(builder.remainingLowerCase) }?.forEach(builder::suggest)
                        builder.buildFuture()
                    }
                    .executes { run(it) { source, runtime -> admin(source, runtime, StringArgumentType.getString(it, "group")) } }),
        )
        .then(
            Commands.literal("token")
                .then(Commands.literal("list").executes { run(it) { source, runtime -> tokens(runtime).forEach { line -> source.sendSystemMessage(Component.literal(line)) } } })
                .then(Commands.literal("create").then(
                    Commands.argument("name", StringArgumentType.word())
                        .executes { run(it) { source, runtime -> create(source, runtime, StringArgumentType.getString(it, "name"), "viewer") } }
                        .then(Commands.argument("group", StringArgumentType.word())
                            .suggests { _, builder ->
                                Mod.runtime?.config?.get()?.auth?.groups?.keys?.filter { name -> name.startsWith(builder.remainingLowerCase) }?.forEach(builder::suggest)
                                builder.buildFuture()
                            }
                            .executes { run(it) { source, runtime -> create(source, runtime, StringArgumentType.getString(it, "name"), StringArgumentType.getString(it, "group")) } }),
                ))
                .then(Commands.literal("revoke").then(
                    Commands.argument("name", StringArgumentType.word())
                        .suggests { _, builder ->
                            Mod.runtime?.api?.tokens?.tokens?.map { it.name }?.filter { name -> name.startsWith(builder.remainingLowerCase) }?.forEach(builder::suggest)
                            builder.buildFuture()
                        }
                        .executes { run(it) { source, runtime -> revoke(source, runtime, StringArgumentType.getString(it, "name")) } },
                )),
        )

    /** The owner of a singleplayer world, an operator, or whoever a permissions mod lets in. */
    fun allowed(source: CommandSourceStack): Boolean {
        val player = source.player
        if (player != null && owner(player)) return true
        return Mod.loader.permission(source, PERMISSION, source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
    }

    /** The player whose game runs this singleplayer world, opened to LAN or not. */
    fun owner(player: ServerPlayer): Boolean = player.level().server.let { !it.isDedicatedServer && it.isSingleplayerOwner(player.nameAndId()) }

    private fun run(context: CommandContext<CommandSourceStack>, block: (CommandSourceStack, Runtime) -> Unit): Int {
        val runtime = Mod.runtime
        if (runtime == null) context.source.sendFailure(Component.literal("WorldAPI is off: the log says why."))
        else block(context.source, runtime)
        return Command.SINGLE_SUCCESS
    }

    /**
     * How to reach this WorldAPI. In singleplayer that's the owner's own machine, so they get config.yml's token behind
     * a button that copies it, and the line that sets minecraft-mcp up; on a server, only the address.
     */
    fun connect(source: CommandSourceStack, runtime: Runtime) {
        val (host, port) = runtime.address ?: run {
            source.sendFailure(Component.literal("WorldAPI's HTTP server is down: the log says why."))
            return
        }
        val url = "http://$host:$port${runtime.config.get().http.basePath}"
        val player = source.player
        if (player == null || !owner(player)) {
            source.sendSystemMessage(Component.literal("WorldAPI listens on $url. Make a token for a client with /worldapi token create <name> [group]."))
            return
        }
        val token = runtime.config.get().auth.tokens.firstOrNull()?.token
        source.sendSystemMessage(Component.literal("WorldAPI is on for this world at ").append(copy(url, url, "Copies the address")).withStyle(ChatFormatting.GRAY))
        if (token != null) {
            source.sendSystemMessage(
                Component.literal("").append(copy("[Copy the token]", token, "The admin token from config/worldapi/config.yml. Keep it to yourself."))
                    .append(Component.literal("  "))
                    .append(copy("[Copy the MCP setup]", MCP_SETUP, "For Claude Code; minecraft-mcp finds this game by itself")),
            )
        }
        source.sendSystemMessage(Component.literal("minecraft-mcp finds the open world without settings. More: github.com/MairwunNx/minecraft-worldapi/wiki/Singleplayer").withStyle(ChatFormatting.DARK_GRAY))
    }

    /** What Claude Code takes to add minecraft-mcp; without WORLDAPI_URL it looks for the open world itself. */
    const val MCP_SETUP = "claude mcp add minecraft -- npx -y @mairwunnx/minecraft-mcp"

    private fun copy(label: String, value: String, hover: String): MutableComponent =
        Component.literal(label).withStyle { style ->
            style.withColor(ChatFormatting.AQUA).withClickEvent(ClickEvent.CopyToClipboard(value)).withHoverEvent(HoverEvent.ShowText(Component.literal(hover)))
        }

    private fun tokens(runtime: Runtime): List<String> {
        val now = Instant.now()
        val config = runtime.config.get()
        val fromConfig = config.auth.tokens.map { "${it.name}: ${it.scopes.joinToString()} (config.yml)" }
        val issued = runtime.api.tokens.tokens.map {
            val state = when {
                it.expired(now) -> "expired ${it.expiresAt}"
                it.expiresAt != null -> "expires ${it.expiresAt}"
                else -> "never expires"
            }
            "${it.name}: ${it.scopes.joinToString()} ($state, made by ${it.createdBy})"
        }
        return (fromConfig + issued).ifEmpty { listOf("No tokens yet. Create one with /worldapi token create <name> [group].") }
    }

    private fun create(source: CommandSourceStack, runtime: Runtime, name: String, group: String) {
        val player = source.player ?: return source.sendFailure(Component.literal("Create tokens in game: the console would write the token to the log."))
        val config = runtime.config.get()
        when {
            !NAME.matches(name) -> return source.sendFailure(Component.literal("Names are 1–32 letters, digits, dashes, or underscores."))
            config.auth.tokens.any { it.name == name } || runtime.api.tokens.tokens.any { it.name == name } -> return source.sendFailure(Component.literal("A token named $name already exists."))
            group !in config.auth.groups -> return source.sendFailure(Component.literal("No group $group in config.yml. Groups: ${config.auth.groups.keys.joinToString()}"))
        }
        val token = runtime.api.tokens.issue(name, listOf(group), player.gameProfile.name(), null)
        Logs.logger.info("${player.gameProfile.name()} made the token \"$name\" in group $group")
        source.sendSystemMessage(Component.literal("Token $name in group $group, shown once:").withStyle(ChatFormatting.GREEN))
        source.sendSystemMessage(copy("[Copy the token]", token, "Copies the token; WorldAPI keeps only its hash"))
    }

    private fun revoke(source: CommandSourceStack, runtime: Runtime, name: String) {
        when {
            runtime.config.get().auth.tokens.any { it.name == name } -> source.sendFailure(Component.literal("$name lives in config.yml; remove it there and run /worldapi reload."))
            !runtime.api.tokens.revoke(name) -> source.sendFailure(Component.literal("No in-game token named $name."))
            else -> {
                Logs.logger.info("Token \"$name\" revoked")
                source.sendSystemMessage(Component.literal("Token $name revoked. Clients using it get 401 from now on.").withStyle(ChatFormatting.GREEN))
            }
        }
    }

    private fun admin(source: CommandSourceStack, runtime: Runtime, group: String?) {
        val player = source.player ?: return source.sendFailure(Component.literal("Get a panel code in game: the console would write it to the log."))
        val groups = runtime.config.get().auth.groups
        val accounts = runtime.api.accounts
        val name = player.gameProfile.name()
        // A quick read on the server thread: the command needs the answer now, and SQLite answers in microseconds.
        val (count, existing) = kotlinx.coroutines.runBlocking { accounts.count() to accounts.find(name) }
        val chosen = group ?: if (count == 0L) "owner" else "admin"
        if (existing == null && chosen !in groups) return source.sendFailure(Component.literal("No group $chosen in config.yml. Groups: ${groups.keys.joinToString()}"))
        val code = if (existing != null) accounts.issueCode(name, player.uuid.toString(), existing.groups, name, reset = true)
        else accounts.issueCode(name, player.uuid.toString(), listOf(chosen), name)
        Logs.logger.info("$name got an admin panel code" + if (existing != null) " to reset their password" else " for an account in group $chosen")
        source.sendSystemMessage(Component.literal(
            if (existing != null) "A code to set a new password for your panel account $name, good for 30 minutes:"
            else "A code for a panel account $name in group $chosen, good for 30 minutes:",
        ).withStyle(ChatFormatting.GREEN))
        source.sendSystemMessage(copy("[$code]", code, "Copies the code").withStyle(ChatFormatting.BOLD))
        val (host, port) = runtime.address ?: return
        val url = runtime.config.get().panel.publicUrl.trim().trimEnd('/').ifEmpty { "http://$host:$port" }
        source.sendSystemMessage(
            Component.literal("[Open the admin panel]").withStyle { style ->
                style.withColor(ChatFormatting.GREEN).withBold(true).withClickEvent(ClickEvent.OpenUrl(java.net.URI.create("$url/admin/?code=$code")))
                    .withHoverEvent(HoverEvent.ShowText(Component.literal("$url/admin/, with the code filled in")))
            },
        )
        source.sendSystemMessage(Component.literal("Then pick a password of at least 10 characters.").withStyle(ChatFormatting.GRAY))
    }
}
