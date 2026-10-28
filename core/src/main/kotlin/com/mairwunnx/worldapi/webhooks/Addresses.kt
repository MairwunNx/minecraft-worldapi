package com.mairwunnx.worldapi.webhooks

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

/** Keeps webhooks that others made from reaching this machine or the network behind it. */
object Addresses {
    /** Whether [address] belongs to this machine, a private or special network, or the cloud's metadata service. */
    fun private(address: InetAddress): Boolean {
        if (address.isLoopbackAddress || address.isAnyLocalAddress || address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress) return true
        val bytes = address.address
        fun byte(index: Int) = bytes[index].toInt() and 0xff
        return when (address) {
            is Inet4Address -> when {
                byte(0) == 0 -> true
                // 100.64.0.0/10, carrier-grade NAT, which clouds use inside too.
                byte(0) == 100 && byte(1) and 0xc0 == 64 -> true
                // 192.0.0.0/24, special purposes, one cloud's metadata among them.
                byte(0) == 192 && byte(1) == 0 && byte(2) == 0 -> true
                // 198.18.0.0/15, benchmarking and fake-IP proxies.
                byte(0) == 198 && byte(1) and 0xfe == 18 -> true
                // 240.0.0.0/4, reserved, broadcast included.
                byte(0) >= 240 -> true
                else -> false
            }
            is Inet6Address -> when {
                // fc00::/7, unique local.
                byte(0) and 0xfe == 0xfc -> true
                // IPv4 inside IPv6 counts as that IPv4 address: mapped and compatible (::a.b.c.d), NAT64 (64:ff9b::/96), 6to4 (2002::/16).
                (0..11).all { byte(it) == 0 } -> private(v4(bytes, 12))
                byte(0) == 0 && byte(1) == 0x64 && byte(2) == 0xff && byte(3) == 0x9b -> if ((4..11).all { byte(it) == 0 }) private(v4(bytes, 12)) else true
                byte(0) == 0x20 && byte(1) == 0x02 -> private(v4(bytes, 2))
                // 2001::/32, Teredo, which hides an address anyone could pick.
                byte(0) == 0x20 && byte(1) == 0x01 && byte(2) == 0 && byte(3) == 0 -> true
                else -> mapped(bytes)?.let(::private) == true
            }
            else -> false
        }
    }

    private fun v4(bytes: ByteArray, from: Int): InetAddress = InetAddress.getByAddress(bytes.copyOfRange(from, from + 4))

    private fun mapped(bytes: ByteArray): InetAddress? =
        if (bytes.size == 16 && (0..9).all { bytes[it].toInt() == 0 } && bytes[10].toInt() and 0xff == 0xff && bytes[11].toInt() and 0xff == 0xff) v4(bytes, 12) else null

    /**
     * Says why WorldAPI won't post to [url], or null when it may. Checked when a webhook is made and before each delivery.
     * Only HTTPS passes: a name that points outside at the check and inside at the connection then meets a certificate
     * that isn't its own, where plain HTTP would go through.
     */
    fun refusal(url: String): String? {
        if (!url.startsWith("https://")) return "only https:// addresses are allowed"
        val host = runCatching { URI.create(url).host }.getOrNull()?.removePrefix("[")?.removeSuffix("]") ?: return "the URL has no host"
        val addresses = runCatching { InetAddress.getAllByName(host).toList() }.getOrElse { return "$host doesn't resolve" }
        return if (addresses.any(::private)) "$host is on this machine or a private network" else null
    }
}
