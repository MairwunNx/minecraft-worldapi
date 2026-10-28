package com.mairwunnx.worldapi.connections

import com.mairwunnx.worldapi.config.WorldApiConfig
import com.mairwunnx.worldapi.connections.ChatLine.Button
import com.mairwunnx.worldapi.connections.ChatLine.Buttons
import com.mairwunnx.worldapi.connections.ChatLine.Text
import com.mairwunnx.worldapi.connections.ChatLine.Tone

/**
 * `/worldapi mcp <name> [group]`: a token for an AI client, shown once with a button per client that copies its setup.
 * The token goes to one player's chat only, as any token does.
 */
object McpChat {
    /** The API address for the setup: http.publicUrl, or this machine's port, which works for a client on the same machine. */
    fun url(config: WorldApiConfig): Pair<String, Boolean> {
        config.http.publicUrl.trim().removeSuffix("/").takeIf { it.isNotEmpty() && McpSetup.validUrl(it) }?.let { return it to true }
        return "http://localhost:${config.http.port}${config.http.basePath}" to false
    }

    fun lines(name: String, group: String, token: String, config: WorldApiConfig): List<ChatLine> {
        val (url, public) = url(config)
        val setup = McpSetup.build(url, token)
        fun copy(scope: String) = setup.clients.filter { it.scope == scope }.map { client ->
            Button("[${client.title}]", if (client.command != null) "Copy the command" else "Copy ${client.file}", copy = client.command ?: client.content)
        }
        return buildList {
            add(Text("MCP connection $name ($group) is ready. Press a client to copy its setup; it holds the token, shown only now.", Tone.GOOD))
            add(Text("For every project:"))
            add(Buttons(copy("user")))
            add(Text("For one project only (run it there, or put the file there and in .gitignore):"))
            add(Buttons(copy("project")))
            if (!public) add(Text("It points at $url, which works on this machine. For another one, set http.publicUrl in WorldAPI's config.yml.", Tone.HINT))
        }
    }
}
