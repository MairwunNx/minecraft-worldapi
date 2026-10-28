package com.mairwunnx.worldapi.model

import kotlinx.serialization.Serializable

@Serializable
data class TokenView(
    val name: String,
    /** Permissions and groups as the token lists them. */
    val scopes: List<String>,
    /** What those come to. */
    val permissions: List<String>,
    /** `config` for tokens in config.yml, `issued` for ones made in game or here. */
    val source: String,
    val createdAt: String? = null,
    val createdBy: String? = null,
    val expiresAt: String? = null,
    val expired: Boolean = false,
    /** `token` for sites and scripts, `mcp` for an AI client. */
    val kind: String = "token",
)

@Serializable
data class TokenRequest(
    /** 1–32 letters, digits, dashes, or underscores. */
    val name: String,
    /** Permissions and group names; only ones the caller has itself. */
    val scopes: List<String>,
    /** Absent or 0 for a token that never expires. */
    val expiresInDays: Int? = null,
    /** `token` for sites and scripts; `mcp` also answers how to add minecraft-mcp to Claude Code, Codex, Cursor, and OpenCode. */
    val kind: String = "token",
    /** For `mcp`: the API address as the caller reaches it, which the setup names. Without it, http.publicUrl, or a guess. */
    val apiUrl: String? = null,
)

@Serializable
data class TokenCreated(
    val name: String,
    /** Shown this once; WorldAPI keeps only its SHA-256. */
    val token: String,
    val scopes: List<String>,
    val expiresAt: String? = null,
    val kind: String = "token",
    /** For `mcp`: commands and files for each AI client, the token inside. */
    val setup: McpSetup? = null,
)

@Serializable
data class PermissionInfo(val id: String, val description: String)

@Serializable
data class GroupsView(
    /** Each group's members as config.yml lists them. */
    val groups: Map<String, List<String>>,
    /** What each group comes to. */
    val resolved: Map<String, List<String>>,
    val permissions: List<PermissionInfo>,
)

@Serializable
data class AccountView(
    val name: String,
    val groups: List<String>,
    val permissions: List<String>,
    val player: String? = null,
    val createdAt: String,
    val createdBy: String,
    val lastLoginAt: String? = null,
)

@Serializable
data class InviteRequest(
    val groups: List<String>,
    /** Names the account ahead; otherwise the person picks a name with the code. */
    val name: String? = null,
)

@Serializable
data class InviteCode(
    val code: String,
    val expiresAt: String,
    val groups: List<String>,
)

@Serializable
data class AccountPatch(val groups: List<String>)

@Serializable
data class AuditView(
    val id: Long,
    val at: String,
    /** The token or `panel:<account>` that acted. */
    val actor: String,
    val method: String,
    val path: String,
    val target: String? = null,
    val status: Int,
    val detail: String? = null,
)

/** How to add minecraft-mcp to AI clients with a new token; see [com.mairwunnx.worldapi.connections.McpSetup]. */
@Serializable
data class McpSetup(
    /** The API address the clients reach WorldAPI at. */
    val url: String,
    val clients: List<McpClientSetup>,
)

@Serializable
data class McpClientSetup(
    /** `claude`, `codex`, `cursor`, `opencode`, or `plain`. */
    val client: String,
    val title: String,
    /** `user`: every project on this machine; `project`: the one the command runs in, or the file sits in. */
    val scope: String,
    /** A command to run, in the project's folder for `project`. */
    val command: String? = null,
    /** A file to write or merge into: a path, `~` for the home folder. */
    val file: String? = null,
    val content: String? = null,
    /** A link the client opens to add it in one click. */
    val link: String? = null,
    /** The file holds the token: add it to .gitignore so it never reaches the repository. */
    val gitignore: String? = null,
    /** The client reads the file only in a project it trusts. */
    val trust: Boolean = false,
    /** For clients set up some other way: the variables minecraft-mcp reads. */
    val env: Map<String, String>? = null,
)
