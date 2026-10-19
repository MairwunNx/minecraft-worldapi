package com.mairwunnx.worldapi.admin

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.HexFormat
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Password hashes with PBKDF2-HMAC-SHA256 from the JDK, so the jar needs no crypto library.
 * Stored as `pbkdf2$<iterations>$<salt hex>$<hash hex>`; a hash records its own cost, so raising
 * [ITERATIONS] later leaves old passwords working.
 */
object Passwords {
    const val ITERATIONS = 600_000
    const val MIN_LENGTH = 10
    private val random = SecureRandom()
    private val hex = HexFormat.of()

    fun hash(password: String, iterations: Int = ITERATIONS): String {
        val salt = ByteArray(16).also(random::nextBytes)
        return "pbkdf2$$iterations$${hex.formatHex(salt)}$${hex.formatHex(derive(password, salt, iterations))}"
    }

    fun verify(password: String, stored: String): Boolean {
        val parts = stored.split('$')
        if (parts.size != 4 || parts[0] != "pbkdf2") return false
        val iterations = parts[1].toIntOrNull() ?: return false
        val expected = runCatching { hex.parseHex(parts[3]) }.getOrNull() ?: return false
        val actual = derive(password, hex.parseHex(parts[2]), iterations)
        return MessageDigest.isEqual(expected, actual)
    }

    /** A hash nobody's password matches, to spend the same time on an unknown name as on a known one. */
    val decoy: String by lazy { hash(hex.formatHex(ByteArray(16).also(random::nextBytes))) }

    private fun derive(password: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, 256)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}
