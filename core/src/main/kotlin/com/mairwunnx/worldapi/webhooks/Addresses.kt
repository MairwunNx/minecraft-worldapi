package com.mairwunnx.worldapi.webhooks

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

/** Keeps webhooks that others made from reaching this machine or the network behind it. */
object Addresses {
    /** Whether [address] belongs to this machine, a private network, or the cloud's metadata service. */
    fun private(address: InetAddress): Boolean {
        if (address.isLoopbackAddress || address.isAnyLocalAddress || address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress) return true
        val bytes = address.address
        return when (address) {
            // 100.64.0.0/10, carrier-grade NAT, which clouds use inside too.
            is Inet4Address -> bytes[0].toInt() and 0xff == 100 && bytes[1].toInt() and 0xc0 == 64 || bytes[0].toInt() == 0
            // fc00::/7, unique local; an IPv4 address inside IPv6 counts as itself.
            is Inet6Address -> bytes[0].toInt() and 0xfe == 0xfc || mapped(bytes)?.let(::private) == true
            else -> false
        }
    }

    private fun mapped(bytes: ByteArray): InetAddress? =
        if (bytes.size == 16 && (0..9).all { bytes[it].toInt() == 0 } && bytes[10].toInt() and 0xff == 0xff && bytes[11].toInt() and 0xff == 0xff) {
            InetAddress.getByAddress(bytes.copyOfRange(12, 16))
        } else {
            null
        }

    /**
     * Resolves the URL's host and says why WorldAPI won't post there, or null when it may. Checked when a webhook is
     * made and before each delivery, so a name that later points inside is caught too.
     */
    fun refusal(url: String): String? {
        val host = runCatching { URI.create(url).host }.getOrNull()?.removePrefix("[")?.removeSuffix("]") ?: return "the URL has no host"
        val addresses = runCatching { InetAddress.getAllByName(host).toList() }.getOrElse { return "$host doesn't resolve" }
        return if (addresses.any(::private)) "$host is on this machine or a private network" else null
    }
}
