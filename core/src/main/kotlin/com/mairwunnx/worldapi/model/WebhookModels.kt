package com.mairwunnx.worldapi.model

import kotlinx.serialization.Serializable

@Serializable
data class WebhookView(
    val name: String,
    /** `json`, `discord`, `slack`, `telegram`, `teams`, or `google_chat`. */
    val format: String,
    /** Where it posts, with the secret part hidden: `https://discord.com/api/webhooks/…`, or the Telegram chat. */
    val target: String,
    val topics: List<String>,
    val permissions: List<String>,
    val language: String,
    val enabled: Boolean,
    /** Its own words, by message key. */
    val messages: Map<String, String> = emptyMap(),
    /** A json webhook signs its bodies. */
    val signed: Boolean,
    /** `config` for config.yml's, which change only there; `made` for ones made in game, in the panel, or through the API. */
    val source: String,
    val createdAt: String? = null,
    val createdBy: String? = null,
)

@Serializable
data class WebhookRequest(
    /** 1–32 letters, digits, dashes, or underscores. */
    val name: String,
    val format: String,
    /** Every format but `telegram`: the URL the service gave, such as Discord's channel webhook. */
    val url: String? = null,
    /** For `telegram`: the bot's token from @BotFather, and the chat; POST /webhooks/telegram/chats finds the chat. */
    val botToken: String? = null,
    val chatId: String? = null,
    /** Topic ids, `player.*`, or `*` for every notable event. */
    val topics: List<String>,
    /** What it may see; only permissions the caller has. Without them: server:read, players:read, events:read. */
    val permissions: List<String> = emptyList(),
    val language: String = "en",
    val messages: Map<String, String> = emptyMap(),
    /** Send a test message right away. On by default. */
    val test: Boolean = true,
)

@Serializable
data class WebhookCreated(
    val webhook: WebhookView,
    /** For a json webhook: the secret that signs its bodies, shown this once. */
    val secret: String? = null,
    val test: WebhookTestResult? = null,
)

@Serializable
data class WebhookPatch(
    val topics: List<String>? = null,
    val permissions: List<String>? = null,
    val language: String? = null,
    val enabled: Boolean? = null,
    /** Replaces its own words; an empty object brings back the defaults. */
    val messages: Map<String, String>? = null,
)

@Serializable
data class WebhookTestResult(
    val ok: Boolean,
    /** What the service answered. */
    val status: Int? = null,
    /** Why it didn't arrive, such as `HTTP 404` or a refused address. */
    val problem: String? = null,
)

@Serializable
data class TelegramChatsRequest(val botToken: String)

@Serializable
data class TelegramChatView(
    /** What `chatId` takes. */
    val id: String,
    val title: String,
    /** `private`, `group`, `supergroup`, or `channel`. */
    val type: String,
)

@Serializable
data class WebhookOptions(
    /** Off in config.yml: no webhook sends, and none can be made. */
    val enabled: Boolean,
    val formats: List<WebhookFormatInfo>,
    val languages: List<String>,
    val topics: List<WebhookTopicInfo>,
    /** Keys a webhook's `messages` may set, with each one's English default. */
    val messages: Map<String, String>,
)

@Serializable
data class WebhookFormatInfo(val id: String, val title: String)

@Serializable
data class WebhookTopicInfo(
    val id: String,
    /** The permission a webhook needs to get it. */
    val needs: String,
    /** Part of `*`: the notable events the history records. */
    val notable: Boolean,
    /** Its data holds coordinates, which a webhook gets only with players:private. */
    val coordinates: Boolean,
    /** A plugin's topic: what the plugin says it is. */
    val description: String? = null,
)
