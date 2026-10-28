package com.mairwunnx.worldapi.webhooks

import com.mairwunnx.worldapi.api.ApiJson
import com.mairwunnx.worldapi.api.Principal
import com.mairwunnx.worldapi.config.Webhook
import com.mairwunnx.worldapi.config.WebhookFormat
import com.mairwunnx.worldapi.config.WorldApiConfig
import com.mairwunnx.worldapi.events.ApiEvent
import com.mairwunnx.worldapi.events.EventSink
import com.mairwunnx.worldapi.events.Topic
import com.mairwunnx.worldapi.events.Visibility
import com.mairwunnx.worldapi.model.EventView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.logging.Logger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Sends events to webhooks as they happen: the ones in config.yml and the ones admins made, which [store] keeps. A
 * webhook sees what a token with its permissions would see on the WebSocket, decided as each event goes out. Each has
 * its own thread and queue, so a slow or dead URL holds up only itself; a full queue drops the oldest event.
 */
class Webhooks(
    private val config: () -> WorldApiConfig,
    private val version: String,
    private val logger: Logger,
    val store: WebhookStore? = null,
) : EventSink {
    /** A webhook and where it came from: config.yml's are the owner's own and may post anywhere. */
    class Entry(val hook: Webhook, val fromConfig: Boolean)

    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build()
    private val senders = ConcurrentHashMap<String, Sender>()

    /** Webhooks warned about plain HTTP, by name and URL, so a reload that keeps one doesn't warn again. */
    private val warned = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var closed = false

    fun all(): List<Entry> = if (!config().webhooks.enabled) emptyList() else config().webhooks.hooks.map { Entry(it, true) } + store?.all.orEmpty().map { Entry(it.hook, false) }

    private fun find(name: String) = all().firstOrNull { it.hook.name == name }

    /** Whether a webhook listens to [topic], so producers don't skip events only a webhook wants. */
    fun wants(topic: Topic) = all().any { it.hook.listens(topic) }

    /** Any thread: queues the event for each webhook that listens to it, never waits on the network. */
    override fun accept(event: ApiEvent) {
        if (closed) return
        val hooks = all()
        // A webhook a reload or an admin removed or renamed keeps no thread.
        if (senders.keys.any { name -> hooks.none { it.hook.name == name } }) {
            senders.entries.removeIf { (name, sender) -> (hooks.none { it.hook.name == name }).also { gone -> if (gone) sender.stop() } }
        }
        for (entry in hooks) {
            if (entry.hook.listens(event.topic)) sender(entry.hook)?.offer(event)
        }
    }

    @Synchronized
    private fun sender(hook: Webhook): Sender? {
        if (closed) return null
        return senders.getOrPut(hook.name) {
            if (plain(hook.target) && warned.add("${hook.name} ${hook.target}")) {
                logger.warning("Webhook ${hook.name} sends events unencrypted over http://; use https:// unless it stays on this machine")
            }
            Sender(hook.name)
        }
    }

    /** Gives queued events a few seconds to go out, then stops. */
    fun close() {
        synchronized(this) { closed = true }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        senders.values.forEach { it.finish(deadline) }
        senders.clear()
        client.shutdownNow()
    }

    /** What a delivery came to: the status the service answered, or why there was none. */
    class Outcome(val status: Int?, val problem: String?, val refused: Boolean = false) {
        val ok get() = status != null && status in 200..299
    }

    /** Sends [hook] a test message right away, for a webhook just made or one an admin checks. Off the server thread. */
    suspend fun test(hook: Webhook, fromConfig: Boolean): Outcome = withContext(Dispatchers.IO) {
        val body = if (hook.format == WebhookFormat.JSON) {
            val data = buildJsonObject { put("message", Messages.test(hook.language)) }
            ApiJson.encodeToString(EventView.serializer(), EventView(null, "webhook.test", Instant.now().toString(), System.currentTimeMillis(), null, null, data))
        } else {
            Formats.message(hook, Messages.test(hook.language)).toString()
        }
        send(hook, fromConfig, body, "webhook.test", UUID.randomUUID().toString())
    }

    /** One chat that wrote to a Telegram bot, so an admin can pick where it posts. */
    class TelegramChat(val id: String, val title: String, val type: String)

    /**
     * The chats that wrote to the bot lately, newest first: someone writes /start to the bot, or adds it to a group, and
     * the chat shows here. Telegram keeps a bot's updates for a day.
     */
    suspend fun telegramChats(botToken: String): List<TelegramChat> = withContext(Dispatchers.IO) {
        val request = HttpRequest.newBuilder(URI.create("https://api.telegram.org/bot$botToken/getUpdates?limit=100&allowed_updates=%5B%22message%22,%22my_chat_member%22,%22channel_post%22%5D"))
            .timeout(Duration.ofSeconds(10)).GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() == 401 || response.statusCode() == 404) throw IllegalArgumentException("Telegram doesn't know this bot token")
        if (response.statusCode() !in 200..299) throw IllegalStateException("Telegram answered ${response.statusCode()}")
        val updates = ApiJson.parseToJsonElement(response.body()).jsonObject["result"]?.jsonArray.orEmpty()
        updates.reversed().mapNotNull { update ->
            val chat = listOf("message", "my_chat_member", "channel_post").firstNotNullOfOrNull { update.jsonObject[it]?.jsonObject?.get("chat")?.jsonObject } ?: return@mapNotNull null
            val title = chat["title"]?.jsonPrimitive?.content
                ?: listOfNotNull(chat["first_name"]?.jsonPrimitive?.content, chat["last_name"]?.jsonPrimitive?.content).joinToString(" ").ifEmpty { null }
                ?: chat["username"]?.jsonPrimitive?.content ?: "?"
            TelegramChat(chat["id"]!!.jsonPrimitive.content, title, chat["type"]?.jsonPrimitive?.content ?: "private")
        }.distinctBy { it.id }
    }

    /** Builds what [hook] gets for [event], or null when it may not see it or there's nothing to say. */
    private fun body(hook: Webhook, event: ApiEvent, settings: WorldApiConfig): String? {
        val visibility = Visibility(principal(hook, settings), settings)
        if (!visibility.allows(event.topic) || !visibility.shows(event)) return null
        val view = visibility.redact(event.view(), event.topic)
        if (hook.format == WebhookFormat.JSON) return ApiJson.encodeToString(EventView.serializer(), view)
        // The message reads from the redacted data, so a stripped location can't come back through the text.
        val text = Messages.text(event.copy(data = view.data), hook.language, hook.messages) ?: return null
        return Formats.message(hook, text).toString()
    }

    /** One POST, with the checks every delivery passes. Never logs the URL or a secret. */
    private fun send(hook: Webhook, fromConfig: Boolean, body: String, topic: String, delivery: String): Outcome {
        if (!fromConfig && !config().webhooks.allowPrivateAddresses) {
            Addresses.refusal(hook.target)?.let { return Outcome(null, it, refused = true) }
        }
        return runCatching { client.send(request(hook, body, topic, delivery), HttpResponse.BodyHandlers.discarding()).statusCode() }
            .onFailure { if (it is InterruptedException) throw it }
            .fold({ Outcome(it, null) }, { Outcome(null, it.javaClass.simpleName) })
    }

    private inner class Sender(private val name: String) {
        private val queue = LinkedBlockingQueue<ApiEvent>(QUEUE)

        @Volatile
        private var failing = false
        private val thread = Thread(::run, "WorldAPI webhook $name").apply { isDaemon = true; start() }

        fun offer(event: ApiEvent) {
            while (!queue.offer(event)) queue.poll()
        }

        fun stop() = thread.interrupt()

        fun finish(deadline: Long) {
            while (queue.isNotEmpty() && System.nanoTime() < deadline) Thread.sleep(50)
            thread.interrupt()
        }

        private fun run() {
            try {
                while (true) {
                    val event = queue.take()
                    // What the webhook is now decides what it gets: a reload or an admin may have changed it since.
                    val entry = find(name) ?: continue
                    if (!entry.hook.listens(event.topic)) continue
                    val body = body(entry.hook, event, config()) ?: continue
                    deliver(entry, body, event.topic.id)
                }
            } catch (_: InterruptedException) {
            }
        }

        /**
         * Tries three times, a second and then five apart; a webhook that keeps failing gets one try per event until it
         * answers again.
         */
        private fun deliver(entry: Entry, body: String, topic: String) {
            val delivery = UUID.randomUUID().toString()
            var problem = ""
            for (attempt in 0 until if (failing) 1 else 3) {
                if (attempt > 0) Thread.sleep(if (attempt == 1) 1_000 else 5_000)
                val outcome = send(entry.hook, entry.fromConfig, body, topic, delivery)
                if (outcome.ok) {
                    if (failing) logger.info("Webhook $name delivers again")
                    failing = false
                    return
                }
                problem = outcome.problem ?: "HTTP ${outcome.status}"
                // A client error or a refused address won't fix itself on a retry, except for a rate limit.
                val status = outcome.status
                if (outcome.refused) break
                if (status != null && status in 400..499 && status != 429) break
            }
            if (!failing) logger.warning("Webhook $name failed ($problem); it tries each event once until it answers again")
            failing = true
        }
    }

    private fun request(hook: Webhook, body: String, topic: String, delivery: String): HttpRequest {
        val at = System.currentTimeMillis().toString()
        return HttpRequest.newBuilder(URI.create(hook.target))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .header("User-Agent", "WorldAPI/$version")
            .apply {
                // Chat services get their message alone; WorldAPI's headers are for JSON receivers that check them.
                if (hook.format == WebhookFormat.JSON) {
                    header("X-WorldAPI-Topic", topic)
                    header("X-WorldAPI-Timestamp", at)
                    header("X-WorldAPI-Delivery", delivery)
                    hook.secret?.let { header("X-WorldAPI-Signature", "sha256=" + sign(it, "$at.$delivery.$body")) }
                }
            }
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
    }

    companion object {
        /** Events one webhook may fall behind by before the oldest are dropped. */
        const val QUEUE = 256

        /** Who a webhook is to [Visibility]: never anonymous, with the permissions its config grants. */
        fun principal(hook: Webhook, config: WorldApiConfig) = Principal("webhook:${hook.name}", config.auth.resolver.resolve(hook.permissions), "webhook:${hook.name}")

        /** HMAC-SHA256 of [text] in hex, which a receiver checks with the same secret. */
        fun sign(secret: String, text: String): String {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            return mac.doFinal(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        }

        /** Plain HTTP to anywhere but this machine. */
        fun plain(url: String): Boolean {
            if (!url.startsWith("http://")) return false
            val host = runCatching { URI.create(url).host }.getOrNull()?.removePrefix("[")?.removeSuffix("]") ?: return true
            return host != "localhost" && host != "::1" && !host.startsWith("127.")
        }
    }
}
