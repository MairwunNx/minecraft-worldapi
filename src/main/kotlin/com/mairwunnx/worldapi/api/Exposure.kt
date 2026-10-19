package com.mairwunnx.worldapi.api

import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Logger

/**
 * Notices when WorldAPI is reached without HTTPS from outside the machine and its local networks:
 * tokens and panel passwords then cross the internet as plain text. WorldAPI can't see the firewall,
 * but every request says where it came from. The console gets a framed warning, at most every
 * [quietMillis]; the panel asks [insecure] to show its own.
 */
class Exposure(private val logger: Logger, private val quietMillis: Long = 6 * 3_600_000L, private val clock: () -> Long = System::currentTimeMillis) {
    private val lastWarning = AtomicLong(Long.MIN_VALUE / 2)

    /** Why a request travelled insecurely, or null when it didn't. */
    fun insecure(call: ApplicationCall, trustedProxies: List<String>): String? {
        val peer = call.request.origin.remoteAddress
        val proxies = trustedProxies.mapNotNull(Cidr::parse)
        val proto = call.request.headers["X-Forwarded-Proto"]?.substringBefore(',')?.trim()?.lowercase()
        return when {
            proxies.any { it.contains(peer) } -> {
                // Behind our own proxy: it knows how the visitor came. Without the header we can't tell, so we don't guess.
                val visitor = call.request.headers["X-Forwarded-For"]?.split(',')?.lastOrNull { !proxies.any { p -> p.contains(it.trim()) } }?.trim()
                if (proto == "http" && visitor != null && !local(visitor)) "through a proxy that serves plain HTTP" else null
            }
            local(peer) -> null
            else -> "straight to port ${call.request.local.localPort}, past any proxy"
        }
    }

    /** Checks a request and warns the console when it came in the open. */
    fun check(call: ApplicationCall, trustedProxies: List<String>) {
        val reason = insecure(call, trustedProxies) ?: return
        val now = clock()
        val last = lastWarning.get()
        if (now - last < quietMillis || !lastWarning.compareAndSet(last, now)) return
        logger.warning("\n" + frame(reason))
    }

    companion object {
        private val LOCAL = listOf("127.0.0.0/8", "::1/128", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "100.64.0.0/10", "169.254.0.0/16", "fc00::/7", "fe80::/10")
            .mapNotNull(Cidr::parse)

        fun local(address: String) = LOCAL.any { it.contains(address) }

        internal fun frame(reason: String): String {
            val lines = listOf(
                "WorldAPI IS REACHABLE WITHOUT HTTPS",
                "",
                "A request came from the internet without HTTPS:",
                "  $reason.",
                "Tokens and admin panel passwords travel as plain text,",
                "and anyone on the way can read and reuse them.",
                "",
                "Close WorldAPI's port to the internet and put a reverse proxy",
                "with HTTPS in front of it (nginx, Caddy, Cloudflare Tunnel),",
                "or reach it through an SSH tunnel:",
                "  ssh -L 8110:localhost:8110 you@your-server",
                "",
                "How: https://github.com/MairwunNx/minecraft-worldapi#http-exposure",
                "This warning repeats every 6 hours while it happens.",
            )
            val width = lines.maxOf { it.length }
            val border = "+" + "-".repeat(width + 4) + "+"
            return (listOf(border) + lines.map { "|  " + it.padEnd(width) + "  |" } + border).joinToString("\n")
        }
    }
}
