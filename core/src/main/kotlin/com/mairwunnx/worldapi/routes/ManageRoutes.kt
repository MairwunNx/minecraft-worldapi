package com.mairwunnx.worldapi.routes

import com.mairwunnx.worldapi.admin.AdminAccounts
import com.mairwunnx.worldapi.admin.canGrant
import com.mairwunnx.worldapi.api.ApiCall
import com.mairwunnx.worldapi.api.ApiException
import com.mairwunnx.worldapi.api.Page
import com.mairwunnx.worldapi.api.Scope
import com.mairwunnx.worldapi.api.endpoints
import com.mairwunnx.worldapi.api.badRequest
import com.mairwunnx.worldapi.api.conflict
import com.mairwunnx.worldapi.api.notFound
import com.mairwunnx.worldapi.api.pageParams
import com.mairwunnx.worldapi.api.string
import com.mairwunnx.worldapi.model.AccountPatch
import com.mairwunnx.worldapi.model.AccountView
import com.mairwunnx.worldapi.model.AuditView
import com.mairwunnx.worldapi.model.GroupsView
import com.mairwunnx.worldapi.model.InviteCode
import com.mairwunnx.worldapi.model.InviteRequest
import com.mairwunnx.worldapi.model.PermissionInfo
import com.mairwunnx.worldapi.model.TokenCreated
import com.mairwunnx.worldapi.model.TokenRequest
import com.mairwunnx.worldapi.model.TokenView
import io.ktor.http.HttpStatusCode
import java.time.Duration
import java.time.Instant

private const val GRANT = "A caller hands out only permissions it has itself."

val manageRoutes = endpoints("Access control", Scope.TOKENS_MANAGE) {
    get("/tokens", "Every token: from config.yml and issued in game or here", description = "Tokens themselves never show; WorldAPI keeps only their SHA-256.") {
        val auth = config.auth
        val now = Instant.now()
        auth.tokens.map { TokenView(it.name, it.scopes, ids(auth.resolver.resolve(it.scopes)), "config") } +
            api.tokens.tokens.map {
                TokenView(it.name, it.scopes, ids(auth.resolver.resolve(it.scopes)), "issued", it.createdAt, it.createdBy, it.expiresAt, it.expired(now))
            }
    }

    post<TokenRequest, TokenCreated>(
        "/tokens", "Issue a token", Scope.TOKENS_MANAGE, status = HttpStatusCode.Created,
        description = "The answer holds the token once. $GRANT",
    ) { request ->
        if (!TOKEN_NAME.matches(request.name)) badRequest("Names are 1–32 letters, digits, dashes, or underscores")
        if (config.auth.tokens.any { it.name == request.name } || api.tokens.tokens.any { it.name == request.name }) conflict("token_exists", "A token named ${request.name} exists")
        val days = request.expiresInDays ?: 0
        if (days !in 0..3_650) badRequest("expiresInDays must be between 0 and 3650")
        if (request.scopes.isEmpty()) badRequest("Give at least one group or permission")
        checkGrant(request.scopes)
        val expires = days.takeIf { it > 0 }?.let { Instant.now().plus(Duration.ofDays(it.toLong())) }
        auditTarget = request.name
        auditDetail = request.scopes.joinToString()
        val token = api.tokens.issue(request.name, request.scopes, principal.name, expires)
        TokenCreated(request.name, token, request.scopes, expires?.toString())
    }

    delete("/tokens/{name}", "Revoke a token made in game or here", Scope.TOKENS_MANAGE, description = "Tokens in config.yml change only there.") {
        val name = path("name")
        if (config.auth.tokens.any { it.name == name }) badRequest("$name lives in config.yml; remove it there and run /worldapi reload", "config_token")
        auditTarget = name
        if (!api.tokens.revoke(name)) notFound("token_not_found", "No issued token $name")
    }

    get("/groups", "Groups from config.yml and every permission", description = "Groups change in config.yml; /worldapi reload applies them.") {
        val auth = config.auth
        GroupsView(
            auth.groups,
            auth.groups.keys.associateWith { ids(auth.resolver.resolve(listOf(it))) },
            Scope.entries.map { PermissionInfo(it.id, it.description) },
        )
    }

    get("/accounts", "Admin panel accounts") {
        val auth = config.auth
        api.accounts.list().map { view(it, ids(auth.resolver.resolve(it.groups))) }
    }

    post<InviteRequest, InviteCode>(
        "/accounts/invite", "Make a code that creates a panel account", Scope.TOKENS_MANAGE, status = HttpStatusCode.Created,
        description = "Send the code to the person; on the panel's sign-in page they enter it with a name and a password within 30 minutes. $GRANT",
    ) { request ->
        if (request.groups.isEmpty()) badRequest("Give at least one group or permission")
        request.name?.let { if (!AdminAccounts.NAME.matches(it)) badRequest("Names are 3–32 letters, digits, dots, dashes, or underscores") }
        checkGrant(request.groups)
        auditTarget = request.name ?: "invite"
        auditDetail = request.groups.joinToString()
        val code = api.accounts.issueCode(request.name, null, request.groups, principal.name)
        InviteCode(code, Instant.now().plusMillis(AdminAccounts.CODE_MILLIS).toString(), request.groups)
    }

    patch<AccountPatch, AccountView>("/accounts/{name}", "Change an account's groups", Scope.TOKENS_MANAGE, description = GRANT) { patch ->
        val name = path("name")
        val account = api.accounts.find(name) ?: notFound("account_not_found", "No account $name")
        if (patch.groups.isEmpty()) badRequest("Give at least one group or permission")
        // Taking a permission away needs the caller to hold it too, or anyone could strip the owner.
        checkGrant(patch.groups + account.groups)
        auditTarget = name
        auditDetail = patch.groups.joinToString()
        api.accounts.setGroups(name, patch.groups)
        view(api.accounts.find(name)!!, ids(config.auth.resolver.resolve(patch.groups)))
    }

    post<com.mairwunnx.worldapi.api.NoBody, InviteCode>(
        "/accounts/{name}/reset", "Make a code that sets a new password for an account", Scope.TOKENS_MANAGE, status = HttpStatusCode.Created,
        description = "For someone who forgot their password and can't run /worldapi admin in game. They enter the code on the sign-in page, under “I have a code”, " +
            "with a new password, within 30 minutes; their sessions end. $GRANT",
    ) {
        val name = path("name")
        val account = api.accounts.find(name) ?: notFound("account_not_found", "No account $name")
        checkGrant(account.groups)
        auditTarget = name
        auditDetail = "password reset code"
        val code = api.accounts.issueCode(name, account.playerUuid, account.groups, principal.name, reset = true)
        InviteCode(code, Instant.now().plusMillis(AdminAccounts.CODE_MILLIS).toString(), account.groups)
    }

    delete("/accounts/{name}", "Delete an account and end its sessions", Scope.TOKENS_MANAGE) {
        val name = path("name")
        if (principal.name == "panel:$name") badRequest("You can't delete your own account")
        val account = api.accounts.find(name) ?: notFound("account_not_found", "No account $name")
        checkGrant(account.groups)
        auditTarget = name
        api.accounts.delete(name)
    }

    get(
        "/audit", "What tokens and accounts changed, newest first",
        query = pageParams + string("actor", "Only this token, or `panel:<account>`"),
    ) {
        val limit = limit.coerceAtLeast(1)
        val before = cursor?.let { it.toLongOrNull() ?: badRequest("Unknown cursor", "invalid_cursor") }
        val rows = api.history.auditLog(before, limit + 1, query("actor"))
        Page(
            rows.take(limit).map { AuditView(it.id, Instant.ofEpochMilli(it.at).toString(), it.entry.token, it.entry.method, it.entry.path, it.entry.target, it.entry.status, it.entry.detail) },
            nextCursor = if (rows.size > limit) rows[limit - 1].id.toString() else null,
        )
    }
}

private val TOKEN_NAME = Regex("[A-Za-z0-9_-]{1,32}")

private fun ids(scopes: Set<Scope>) = scopes.sortedBy { it.ordinal }.map { it.id }

/** Refuses permissions the caller doesn't hold itself: whatever it makes gets as much as it has, never more. */
fun ApiCall<*>.checkGrant(names: List<String>) {
    val unknown = names.filterNot(config.auth.resolver::contains)
    if (unknown.isNotEmpty()) badRequest("Unknown permissions or groups: ${unknown.joinToString()}")
    val wanted = config.auth.resolver.resolve(names)
    if (!principal.canGrant(wanted)) {
        throw ApiException(HttpStatusCode.Forbidden, "cannot_grant", "You can't hand out ${(wanted - principal.scopes).joinToString { it.id }}: you don't have it")
    }
}

private fun view(account: AdminAccounts.Account, permissions: List<String>) = AccountView(
    account.name, account.groups, permissions, account.playerUuid, Instant.ofEpochMilli(account.createdAt).toString(), account.createdBy,
    account.lastLoginAt?.let { Instant.ofEpochMilli(it).toString() },
)
