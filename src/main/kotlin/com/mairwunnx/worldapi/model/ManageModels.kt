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
)

@Serializable
data class TokenRequest(
    /** 1–32 letters, digits, dashes, or underscores. */
    val name: String,
    /** Permissions and group names; only ones the caller has itself. */
    val scopes: List<String>,
    /** Absent or 0 for a token that never expires. */
    val expiresInDays: Int? = null,
)

@Serializable
data class TokenCreated(
    val name: String,
    /** Shown this once; WorldAPI keeps only its SHA-256. */
    val token: String,
    val scopes: List<String>,
    val expiresAt: String? = null,
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
