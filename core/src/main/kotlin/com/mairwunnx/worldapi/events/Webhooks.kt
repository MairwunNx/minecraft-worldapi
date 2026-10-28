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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.logging.Logger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Sends events to the URLs in config.yml `webhooks` as they happen, each a POST with the event as JSON. A webhook sees
 * what a token with its permissions would see on the WebSocket. Each has its own thread and queue, so a slow or dead URL
 * holds up only itself; a full queue drops the oldest event.
 */
class Webhooks(private val config: () -> WorldApiConfig, private val version: String, private val logger: Logger) : EventSink {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build()
    private val senders = ConcurrentHashMap<String, Sender>()

    @Volatile
    private var closed = false

    /** Whether a webhook listens to [topic], so producers don't skip events only a webhook wants. */
    fun wants(topic: Topic) = config().webhooks.any { topic.id in it.topics }

    /** Server thread: decides who may get the event and queues it, never waits on the network. */
    override fun accept(event: ApiEvent) {
        if (closed) return
        val settings = config()
        for (hook in settings.webhooks) {
            if (event.topic.id !in hook.topics) continue
            val visibility = Visibility(principal(hook, settings), settings)
            if (!visibility.allows(event.topic) || !visibility.shows(event)) continue
            val body = ApiJson.encodeToString(EventView.serializer(), visibility.redact(event.view(), event.topic))
            senders.computeIfAbsent(hook.name) { Sender(it) }.offer(body, event.topic.id)
        }
    }

    /** Gives queued events a few seconds to go out, then stops. */
    fun close() {
        closed = true
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        senders.values.forEach { it.finish(deadline) }
        senders.clear()
    }

    private inner class Sender(private val name: String) {
        private val queue = LinkedBlockingQueue<Pair<String, String>>(QUEUE)
        private var failing = false
        private val thread = Thread(::run, "WorldAPI webhook $name").apply { isDaemon = true; start() }

        fun offer(body: String, topic: String) {
            while (!queue.offer(body to topic)) queue.poll()
        }

        fun finish(deadline: Long) {
            while (queue.isNotEmpty() && System.nanoTime() < deadline) Thread.sleep(50)
            thread.interrupt()
        }

        private fun run() {
            try {
                while (true) {
                    val (body, topic) = queue.take()
                    // Config reloads may change or remove the webhook; an event for a removed one goes nowhere.
                    val hook = config().webhooks.firstOrNull { it.name == name } ?: continue
                    deliver(hook, body, topic)
                }
            } catch (_: InterruptedException) {
            }
        }

        /** Tries three times, a second and then five apart; the URL and the secret never reach the log. */
        private fun deliver(hook: Webhook, body: String, topic: String) {
            var problem = ""
            for (attempt in 0 until 3) {
                if (attempt > 0) Thread.sleep(if (attempt == 1) 1_000 else 5_000)
                val status = runCatching { client.send(request(hook, body, topic), HttpResponse.BodyHandlers.discarding()).statusCode() }
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
            if (!failing) logger.warning("Webhook $name failed ($problem) and drops events until it answers again")
            failing = true
        }
    }

    private fun request(hook: Webhook, body: String, topic: String): HttpRequest {
        val at = System.currentTimeMillis().toString()
        return HttpRequest.newBuilder(URI.create(hook.url))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .header("User-Agent", "WorldAPI/$version")
            .header("X-WorldAPI-Topic", topic)
            .header("X-WorldAPI-Timestamp", at)
            .apply { hook.secret?.let { header("X-WorldAPI-Signature", "sha256=" + sign(it, "$at.$body")) } }
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
            mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
            return mac.doFinal(text.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }
}
