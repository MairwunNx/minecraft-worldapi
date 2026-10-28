package com.mairwunnx.worldapi.webhooks

import com.mairwunnx.worldapi.api.ApiException
import com.mairwunnx.worldapi.api.Scope
import com.mairwunnx.worldapi.api.badRequest
import com.mairwunnx.worldapi.api.conflict
import com.mairwunnx.worldapi.config.ConfigFile
import com.mairwunnx.worldapi.config.Webhook
import com.mairwunnx.worldapi.config.WebhookFormat
import com.mairwunnx.worldapi.config.WorldApiConfig
import com.mairwunnx.worldapi.core.WorldApi
import com.mairwunnx.worldapi.model.WebhookCreated
import com.mairwunnx.worldapi.model.WebhookRequest
import com.mairwunnx.worldapi.model.WebhookTestResult
import com.mairwunnx.worldapi.routes.view
import io.ktor.http.HttpStatusCode

/** Webhooks the database keeps at most: each has a thread of its own. */
const val MAX_WEBHOOKS = 50

/** Topics one webhook may list: every check of an event walks them. */
const val MAX_TOPICS = 64

/** What a webhook sees unless its maker says otherwise: no coordinates and no chat, since it usually posts to a shared channel. */
val DEFAULT_WEBHOOK_PERMISSIONS = listOf("server:read", "players:read", "events:read")

/**
 * Makes a webhook for [by], who holds [scopes]: through the API, the panel, or the game, with the same checks. It gets no
 * permission its maker lacks, can't post inside unless config.yml allows it, and sends a test message when asked.
 */
suspend fun WorldApi.createWebhook(request: WebhookRequest, by: String, scopes: Set<Scope>): WebhookCreated {
    val settings = config
    val store = webhooks.store ?: throw ApiException(HttpStatusCode.ServiceUnavailable, "no_store", "Webhooks can't be saved here")
    if (!settings.webhooks.enabled) conflict("webhooks_off", "Webhooks are off in config.yml")
    if (webhooks.all().any { it.hook.name == request.name }) conflict("webhook_exists", "A webhook named ${request.name} exists")
    val format = WebhookFormat.of(request.format) ?: badRequest("format must be one of ${WebhookFormat.entries.joinToString { it.id }}")
    val permissions = request.permissions.ifEmpty { DEFAULT_WEBHOOK_PERMISSIONS }
    checkGrant(settings, scopes, permissions)
    val hook = buildWebhook {
        Webhook(
            name = request.name, format = format, url = request.url?.trim(), botToken = request.botToken?.trim(), chatId = request.chatId?.trim(),
            topics = request.topics.distinct(), permissions = permissions, language = request.language,
            secret = if (format == WebhookFormat.JSON) ConfigFile.newToken() else null, messages = request.messages,
        )
    }
    checkTarget(settings, hook)
    if (store.all.size >= MAX_WEBHOOKS) conflict("too_many_webhooks", "There are $MAX_WEBHOOKS webhooks already; delete one first")
    if (!store.add(hook, by)) conflict("webhook_exists", "A webhook named ${request.name} exists")
    val test = if (request.test) result(webhooks.test(hook, fromConfig = false)) else null
    return WebhookCreated(view(hook, store.find(hook.name)), hook.secret, test)
}

/** Refuses permissions [scopes] don't cover: whatever someone makes gets as much as they have, never more. */
fun checkGrant(config: WorldApiConfig, scopes: Set<Scope>, names: List<String>) {
    val unknown = names.filterNot(config.auth.resolver::contains)
    if (unknown.isNotEmpty()) badRequest("Unknown permissions or groups: ${unknown.joinToString()}")
    val wanted = config.auth.resolver.resolve(names)
    if (!scopes.containsAll(wanted)) {
        throw ApiException(HttpStatusCode.Forbidden, "cannot_grant", "You can't hand out ${(wanted - scopes).joinToString { it.id }}: you don't have it")
    }
}

/** Builds a webhook, turning what its checks refuse into 400, and checks what the config file would. */
fun buildWebhook(build: () -> Webhook): Webhook {
    val hook = runCatching(build).getOrElse { badRequest(it.message ?: "Invalid webhook", "invalid_webhook") }
    if (hook.topics.size > MAX_TOPICS) badRequest("A webhook takes $MAX_TOPICS topics at most; `*` or `player.*` cover many", "too_many_topics")
    hook.topics.filterNot(ConfigFile::knownTopic).takeIf { it.isNotEmpty() }?.let { badRequest("Unknown topics: ${it.joinToString()}", "unknown_topic") }
    hook.messages.keys.filterNot(Messages.keys::contains).takeIf { it.isNotEmpty() }?.let { badRequest("Unknown messages: ${it.joinToString()}", "unknown_message") }
    return hook
}

/** Refuses an address on this machine or a private network, unless config.yml allows them. */
fun checkTarget(config: WorldApiConfig, hook: Webhook) {
    if (config.webhooks.allowPrivateAddresses) return
    Addresses.refusal(hook.target)?.let { throw ApiException(HttpStatusCode.BadRequest, "private_address", "Webhooks can't post there: $it") }
}

fun result(outcome: Webhooks.Outcome) = WebhookTestResult(outcome.ok, outcome.status, outcome.problem ?: outcome.status?.takeIf { !outcome.ok }?.let { "HTTP $it" })
