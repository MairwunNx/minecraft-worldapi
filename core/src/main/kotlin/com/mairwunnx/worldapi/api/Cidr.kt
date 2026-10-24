package com.mairwunnx.worldapi.api

import java.math.BigInteger
import java.net.InetAddress

/** An address range like `10.0.0.0/8`; a bare address means just that address. */
class Cidr private constructor(private val network: ByteArray, private val prefix: Int) {
    fun contains(address: String): Boolean {
        val bytes = literal(address) ?: return false
        if (bytes.size != network.size) return false
        val bits = bytes.size * 8
        val mask = if (prefix == 0) BigInteger.ZERO else BigInteger.ONE.shiftLeft(bits).subtract(BigInteger.ONE).shiftRight(bits - prefix).shiftLeft(bits - prefix)
        return BigInteger(1, bytes).and(mask) == BigInteger(1, network).and(mask)
    }

    companion object {
        fun parse(text: String): Cidr? {
            val (address, length) = text.trim().split('/', limit = 2).let { it[0] to it.getOrNull(1) }
            val bytes = literal(address) ?: return null
            val prefix = length?.toIntOrNull() ?: (bytes.size * 8)
            if (prefix !in 0..bytes.size * 8) return null
            return Cidr(bytes, prefix)
        }

        /** Only literal addresses: InetAddress.getByName would otherwise look host names up in DNS. */
        private fun literal(address: String): ByteArray? {
            val clean = address.trim().removePrefix("[").removeSuffix("]")
            if (!Regex("^[0-9.]+$|^[0-9a-fA-F:.]+$").matches(clean) || ':' !in clean && clean.count { it == '.' } != 3) return null
            return runCatching { InetAddress.getByName(clean).address }.getOrNull()
        }
    }
}
