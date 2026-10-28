package com.mairwunnx.worldapi.webhooks

import com.mairwunnx.worldapi.config.Webhook
import com.mairwunnx.worldapi.config.WebhookFormat
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The body each chat service takes for a plain message. Players write some of the text, chat above all, so none of
 * them may mention everyone: Discord gets no mentions at all, Slack and Google Chat get their markup escaped, and
 * Telegram gets no parse mode.
 */
object Formats {
    fun message(hook: Webhook, text: String): JsonObject = when (hook.format) {
        WebhookFormat.DISCORD -> buildJsonObject {
            put("content", text.take(2_000))
            putJsonObject("allowed_mentions") { putJsonArray("parse") {} }
        }
        WebhookFormat.SLACK -> buildJsonObject { put("text", slack(text)) }
        WebhookFormat.GOOGLE_CHAT -> buildJsonObject { put("text", slack(text)) }
        WebhookFormat.TELEGRAM -> buildJsonObject {
            put("chat_id", hook.chatId)
            put("text", text.take(4_096))
            put("disable_web_page_preview", true)
        }
        WebhookFormat.TEAMS -> buildJsonObject {
            put("type", "message")
            putJsonArray("attachments") {
                addJsonObject {
                    put("contentType", "application/vnd.microsoft.card.adaptive")
                    putJsonObject("content") {
                        put("\$schema", "http://adaptivecards.io/schemas/adaptive-card.json")
                        put("type", "AdaptiveCard")
                        put("version", "1.4")
                        putJsonArray("body") {
                            addJsonObject {
                                put("type", "TextBlock")
                                put("text", text)
                                put("wrap", true)
                            }
                        }
                    }
                }
            }
        }
        WebhookFormat.JSON -> error("A json webhook sends the event itself")
    }

    /** Slack's and Google Chat's escaping: `<!channel>` and `<users/all>` mention everyone, and only these three need it. */
    private fun slack(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
