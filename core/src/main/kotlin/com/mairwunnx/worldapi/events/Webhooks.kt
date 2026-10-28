package com.mairwunnx.worldapi.events

import com.mairwunnx.worldapi.api.ApiJson
import com.mairwunnx.worldapi.api.Principal
import com.mairwunnx.worldapi.config.Webhook
import com.mairwunnx.worldapi.config.WorldApiConfig
import com.mairwunnx.worldapi.model.EventView
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.logging.Logger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Sends events to the URLs in config.yml `webhooks` as they happen, each a POST with the event as JSON. A webhook sees
 * what a token with its permissions would see on the WebSocket, decided as each event goes out. Each has its own thread
 * and queue, so a slow or dead URL holds up only itself; a full queue drops the oldest event.
 */
class Webhooks(private val config: () -> WorldApiConfig, private val version: String, private val logger: Logger) : EventSink {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build()
    private val senders = ConcurrentHashMap<String, Sender>()

    /** Webhooks warned about plain HTTP, by name and URL, so a reload that keeps one doesn't warn again. */
    private val warned = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var closed = false

    /** Whether a webhook listens to [topic], so producers don't skip events only a webhook wants. */
    fun wants(topic: Topic) = config().webhooks.any { topic.id in it.topics }

    /** Any thread: queues the event for each webhook that listens to it, never waits on the network. */
    override fun accept(event: ApiEvent) {
        if (closed) return
        val hooks = config().webhooks
        // A webhook a reload removed or renamed keeps no thread.
        if (senders.keys.any { name -> hooks.none { it.name == name } }) {
            senders.entries.removeIf { (name, sender) -> (hooks.none { it.name == name }).also { gone -> if (gone) sender.stop() } }
        }
        for (hook in hooks) {
            if (event.topic.id in hook.topics) sender(hook)?.offer(event)
        }
    }

    @Synchronized
    private fun sender(hook: Webhook): Sender? {
        if (closed) return null
        return senders.getOrPut(hook.name) {
            if (plain(hook.url) && warned.add("${hook.name} ${hook.url}")) {
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
                    // What the webhook is now decides what it gets: a reload may have changed its URL or permissions since.
                    val settings = config()
                    val hook = settings.webhooks.firstOrNull { it.name == name } ?: continue
                    if (event.topic.id !in hook.topics) continue
                    val visibility = Visibility(principal(hook, settings), settings)
                    if (!visibility.allows(event.topic) || !visibility.shows(event)) continue
                    val body = ApiJson.encodeToString(EventView.serializer(), visibility.redact(event.view(), event.topic))
                    deliver(hook, body, event.topic.id)
                }
            } catch (_: InterruptedException) {
            }
        }

        /**
         * Tries three times, a second and then five apart; a webhook that keeps failing gets one try per event until it
         * answers again. The URL and the secret never reach the log.
         */
        private fun deliver(hook: Webhook, body: String, topic: String) {
            val delivery = UUID.randomUUID().toString()
            var problem = ""
            for (attempt in 0 until if (failing) 1 else 3) {
                if (attempt > 0) Thread.sleep(if (attempt == 1) 1_000 else 5_000)
                val status = runCatching { client.send(request(hook, body, topic, delivery), HttpResponse.BodyHandlers.discarding()).statusCode() }
                    .onFailure { if (it is InterruptedException) throw it }
                    .getOrElse { problem = it.javaClass.simpleName; null }
                if (status != null && status in 200..299) {
                    if (failing) logger.info("Webhook $name delivers again")
                    failing = false
                    return
                }
                if (status != null) problem = "HTTP $status"
                // A client error won't fix itself on a retry, except for a rate limit.
                if (status != null && status in 400..499 && status != 429) break
            }
            if (!failing) logger.warning("Webhook $name failed ($problem); it tries each event once until it answers again")
            failing = true
        }
    }

    private fun request(hook: Webhook, body: String, topic: String, delivery: String): HttpRequest {
        val at = System.currentTimeMillis().toString()
        return HttpRequest.newBuilder(URI.create(hook.url))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .header("User-Agent", "WorldAPI/$version")
            .header("X-WorldAPI-Topic", topic)
            .header("X-WorldAPI-Timestamp", at)
            .header("X-WorldAPI-Delivery", delivery)
            .apply { hook.secret?.let { header("X-WorldAPI-Signature", "sha256=" + sign(it, "$at.$delivery.$body")) } }
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
