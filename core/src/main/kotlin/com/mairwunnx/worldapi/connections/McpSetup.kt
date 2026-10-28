package com.mairwunnx.worldapi.connections

import com.mairwunnx.worldapi.model.McpClientSetup
import com.mairwunnx.worldapi.model.McpSetup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.Base64

/**
 * How to add minecraft-mcp to each AI client with a new token: a command to run or a file to write, for the whole
 * machine or for one project. The token sits in the result once, as it does in the answer that made it.
 */
object McpSetup {
    private const val PACKAGE = "@mairwunnx/minecraft-mcp"
    private const val SERVER = "minecraft"

    /** What an API address may hold; it lands in shell commands and files, so nothing that quotes or splits them. */
    private val URL = Regex("https?://[A-Za-z0-9.:\\[\\]_~%/-]{1,300}")

    fun validUrl(url: String) = URL.matches(url)

    private val pretty = Json { prettyPrint = true; prettyPrintIndent = "  " }

    fun build(url: String, token: String): McpSetup {
        require(validUrl(url)) { "The API address can't go into a command: $url" }
        require(token.all { it.isLetterOrDigit() }) { "A token is hex" }
        val env = linkedMapOf("WORLDAPI_URL" to url, "WORLDAPI_TOKEN" to token)
        val envFlags = { flag: String -> env.entries.joinToString(" ") { "$flag ${it.key}=${it.value}" } }
        val stdio = buildJsonObject {
            put("command", "npx")
            putJsonArray("args") { add("-y"); add(PACKAGE) }
            putJsonObject("env") { env.forEach { (key, value) -> put(key, value) } }
        }
        val mcpServers = pretty.encodeToString(JsonObject.serializer(), buildJsonObject { putJsonObject("mcpServers") { put(SERVER, stdio) } })
        val codex = """
            |[mcp_servers.$SERVER]
            |command = "npx"
            |args = ["-y", "$PACKAGE"]
            |env = { ${env.entries.joinToString(", ") { "${it.key} = \"${it.value}\"" }} }
            |""".trimMargin()
        val opencode = pretty.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("\$schema", "https://opencode.ai/config.json")
            putJsonObject("mcp") {
                putJsonObject(SERVER) {
                    put("type", "local")
                    putJsonArray("command") { add("npx"); add("-y"); add(PACKAGE) }
                    putJsonObject("environment") { env.forEach { (key, value) -> put(key, value) } }
                    put("enabled", true)
                }
            }
        })
        val cursorLink = "cursor://anysphere.cursor-deeplink/mcp/install?name=$SERVER&config=" +
            Base64.getEncoder().encodeToString(stdio.toString().toByteArray())

        return McpSetup(url, listOf(
            McpClientSetup("claude", "Claude Code", "user", command = "claude mcp add $SERVER --scope user ${envFlags("-e")} -- npx -y $PACKAGE"),
            // Claude's local scope is this project for you alone: it lives in ~/.claude.json, never in the repository.
            McpClientSetup("claude", "Claude Code", "project", command = "claude mcp add $SERVER --scope local ${envFlags("-e")} -- npx -y $PACKAGE"),
            McpClientSetup("codex", "Codex", "user", command = "codex mcp add $SERVER ${envFlags("--env")} -- npx -y $PACKAGE"),
            McpClientSetup("codex", "Codex", "project", file = ".codex/config.toml", content = codex, gitignore = ".codex/config.toml", trust = true),
            McpClientSetup("cursor", "Cursor", "user", file = "~/.cursor/mcp.json", content = mcpServers, link = cursorLink),
            McpClientSetup("cursor", "Cursor", "project", file = ".cursor/mcp.json", content = mcpServers, gitignore = ".cursor/mcp.json"),
            McpClientSetup("opencode", "OpenCode", "user", file = "~/.config/opencode/opencode.json", content = opencode),
            McpClientSetup("opencode", "OpenCode", "project", file = "opencode.json", content = opencode, gitignore = "opencode.json"),
            McpClientSetup("plain", "Any MCP client", "user", file = "mcpServers", content = mcpServers, env = env),
        ))
    }
}
