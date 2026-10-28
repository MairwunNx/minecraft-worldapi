package com.mairwunnx.worldapi.routes

import com.mairwunnx.worldapi.api.ApiCall
import com.mairwunnx.worldapi.api.ApiException
import com.mairwunnx.worldapi.api.NoBody
import com.mairwunnx.worldapi.api.Scope
import com.mairwunnx.worldapi.api.badRequest
import com.mairwunnx.worldapi.api.conflict
import com.mairwunnx.worldapi.api.endpoints
import com.mairwunnx.worldapi.api.notFound
import com.mairwunnx.worldapi.config.ConfigFile
import com.mairwunnx.worldapi.config.WEBHOOK_LANGUAGES
import com.mairwunnx.worldapi.config.Webhook
import com.mairwunnx.worldapi.config.WebhookFormat
import com.mairwunnx.worldapi.events.Topic
import com.mairwunnx.worldapi.model.TelegramChatView
import com.mairwunnx.worldapi.model.TelegramChatsRequest
import com.mairwunnx.worldapi.model.WebhookCreated
import com.mairwunnx.worldapi.model.WebhookFormatInfo
import com.mairwunnx.worldapi.model.WebhookOptions
import com.mairwunnx.worldapi.model.WebhookPatch
import com.mairwunnx.worldapi.model.WebhookRequest
import com.mairwunnx.worldapi.model.WebhookTestResult
import com.mairwunnx.worldapi.model.WebhookTopicInfo
import com.mairwunnx.worldapi.model.WebhookView
import com.mairwunnx.worldapi.webhooks.Addresses
import com.mairwunnx.worldapi.webhooks.Messages
import com.mairwunnx.worldapi.webhooks.DEFAULT_WEBHOOK_PERMISSIONS
import com.mairwunnx.worldapi.webhooks.WebhookStore
import com.mairwunnx.worldapi.webhooks.buildWebhook
import com.mairwunnx.worldapi.webhooks.createWebhook
import com.mairwunnx.worldapi.webhooks.result
import com.mairwunnx.worldapi.webhooks.Webhooks
import io.ktor.http.HttpStatusCode
import java.time.Instant

private const val GRANT = "A webhook gets only permissions its maker has, and changing or deleting one needs every permission it holds."

val webhookRoutes = endpoints("Webhooks", Scope.WEBHOOKS_MANAGE) {
    get<WebhookOptions>("/webhooks/options", "What a webhook can be: formats, languages, topics, and the messages it may reword") {
        WebhookOptions(
            enabled = config.webhooks.enabled,
            formats = WebhookFormat.entries.map { WebhookFormatInfo(it.id, it.title) },
            languages = WEBHOOK_LANGUAGES,
            topics = Topic.entries.filterNot { it.state }.map { topic ->
                WebhookTopicInfo(topic.id, topic.scope.id, topic.recorded, topic.privacy == Topic.Privacy.COORDINATES, topic.extension?.description)
            },
            messages = Messages.keys.associateWith { Messages.default("en", it)!! },
        )
    }

    get<List<WebhookView>>(
        "/webhooks", "Every webhook: from config.yml and made in game, in the panel, or here",
        description = "URLs and bot tokens never show in full: they hold the service's secret.",
    ) {
        api.webhooks.all().map { view(it.hook, if (it.fromConfig) null else api.webhooks.store?.find(it.hook.name)) }
    }

    post<WebhookRequest, WebhookCreated>(
        "/webhooks", "Make a webhook: events to Discord, Slack, Telegram, Teams, Google Chat, or any URL as JSON", Scope.WEBHOOKS_MANAGE,
        status = HttpStatusCode.Created,
        description = "Chat formats get a message in the webhook's language; `json` gets the event as the WebSocket sends it, signed with a secret " +
            "the answer shows once. It can't post to this machine or a private network unless config.yml allows it. $GRANT",
    ) { request ->
        auditTarget = request.name
        auditDetail = "${request.format}: ${request.topics.joinToString()}"
        api.createWebhook(request, principal.name, principal.scopes)
    }

    patch<WebhookPatch, WebhookView>(
        "/webhooks/{name}", "Change what a webhook gets, its language or words, or pause it", Scope.WEBHOOKS_MANAGE,
        description = "Webhooks in config.yml change only there. $GRANT",
    ) { patch ->
        val store = stored()
        val current = madeHook(store)
        val permissions = patch.permissions?.ifEmpty { DEFAULT_WEBHOOK_PERMISSIONS }
        checkGrant(current.permissions + permissions.orEmpty())
        val hook = buildWebhook {
            current.copy(
                topics = patch.topics?.distinct() ?: current.topics,
                permissions = permissions ?: current.permissions,
                language = patch.language ?: current.language,
                enabled = patch.enabled ?: current.enabled,
                messages = patch.messages ?: current.messages,
            )
        }
        auditTarget = hook.name
        auditDetail = listOfNotNull(
            patch.topics?.let { "topics: ${it.joinToString()}" }, patch.permissions?.let { "permissions: ${it.joinToString()}" },
            patch.language?.let { "language: $it" }, patch.enabled?.let { if (it) "resumed" else "paused" }, patch.messages?.let { "messages: ${it.keys.joinToString()}" },
        ).joinToString("; ")
        store.replace(hook)
        view(hook, store.find(hook.name))
    }

    delete("/webhooks/{name}", "Delete a webhook", Scope.WEBHOOKS_MANAGE, description = "Webhooks in config.yml change only there. $GRANT") {
        val store = stored()
        val hook = madeHook(store)
        checkGrant(hook.permissions)
        auditTarget = hook.name
        store.remove(hook.name)
        Unit
    }

    post<NoBody, WebhookTestResult>("/webhooks/{name}/test", "Send a webhook a test message", Scope.WEBHOOKS_MANAGE) {
        val name = path("name")
        val entry = api.webhooks.all().firstOrNull { it.hook.name == name } ?: notFound("webhook_not_found", "No webhook $name")
        auditTarget = name
        result(api.webhooks.test(entry.hook, entry.fromConfig))
    }

    post<TelegramChatsRequest, List<TelegramChatView>>(
        "/webhooks/telegram/chats", "Find the chats a Telegram bot can write to", Scope.WEBHOOKS_MANAGE,
        description = "Write /start to the bot, or add it to a group or channel, then ask: the chats that wrote to it lately come back, newest first, " +
            "and one's `id` goes into a webhook's `chatId`. Telegram keeps a bot's updates for a day.",
    ) { request ->
        if (!Webhook.TELEGRAM_TOKEN.matches(request.botToken.trim())) badRequest("That isn't a bot token from @BotFather, like 123456:ABC-DEF…")
        val chats = runCatching { api.webhooks.telegramChats(request.botToken.trim()) }.getOrElse { error ->
            if (error is IllegalArgumentException) badRequest(error.message ?: "Telegram refused the token", "telegram_token")
            throw ApiException(HttpStatusCode.BadGateway, "telegram_unreachable", "Telegram didn't answer: ${error.javaClass.simpleName}")
        }
        chats.map { TelegramChatView(it.id, it.title, it.type) }
    }
}

private fun ApiCall<*>.stored(): WebhookStore = api.webhooks.store ?: throw ApiException(HttpStatusCode.ServiceUnavailable, "no_store", "Webhooks can't be saved here")

/** The webhook `{name}`, which must be one made here: config.yml's change only there. */
private fun ApiCall<*>.madeHook(store: WebhookStore): Webhook {
    val name = path("name")
    if (config.webhooks.hooks.any { it.name == name }) badRequest("$name lives in config.yml; change it there and run /worldapi reload", "config_webhook")
    return store.find(name)?.hook ?: notFound("webhook_not_found", "No webhook $name")
}

/** A webhook as clients see it: the secret part of its address hidden. */
fun view(hook: Webhook, stored: WebhookStore.Stored?) = WebhookView(
    name = hook.name,
    format = hook.format.id,
    target = masked(hook),
    topics = hook.topics,
    permissions = hook.permissions,
    language = hook.language,
    enabled = hook.enabled,
    messages = hook.messages,
    signed = hook.secret != null,
    source = if (stored == null) "config" else "made",
    createdAt = stored?.let { Instant.ofEpochMilli(it.createdAt).toString() },
    createdBy = stored?.createdBy,
)

/** `https://discord.com/api/…`: the host and the first segment, which say what it is without the secret behind it. */
fun masked(hook: Webhook): String {
    if (hook.format == WebhookFormat.TELEGRAM) return "Telegram chat ${hook.chatId}"
    val uri = runCatching { java.net.URI.create(hook.url!!) }.getOrNull() ?: return "…"
    val first = uri.path.orEmpty().split('/').firstOrNull { it.isNotEmpty() }
    return "${uri.scheme}://${uri.host}${uri.port.takeIf { it > 0 }?.let { ":$it" }.orEmpty()}/" + (first?.let { "$it/…" } ?: "…")
}
