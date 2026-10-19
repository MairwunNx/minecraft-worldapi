package com.mairwunnx.worldapi.tokens

import com.charleskorn.kaml.Yaml
import com.mairwunnx.worldapi.config.ConfigFile
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** A token made in game. Only its SHA-256 digest is kept; the token itself is shown once. */
@Serializable
data class IssuedToken(
    val name: String,
    val sha256: String,
    /** Permissions and group names, as in config.yml. */
    val scopes: List<String>,
    val createdAt: String,
    val createdBy: String,
    /** Absent for a token that never expires. */
    val expiresAt: String? = null,
) {
    fun expired(now: Instant = Instant.now()) = expiresAt?.let { Instant.parse(it) <= now } ?: false
}

@Serializable
private data class TokenFile(val tokens: List<IssuedToken> = emptyList())

/**
 * tokens.yml: tokens created with /worldapi token create. Changes write the whole file to a
 * temporary copy first and then move it into place, so a crash never leaves it half written.
 */
class TokenStore(private val path: Path) {
    @Volatile
    var tokens: List<IssuedToken> = emptyList()
        private set

    /** Bumps on every change, so the authenticator knows to rebuild. */
    @Volatile
    var generation = 0
        private set

    fun load() {
        tokens = if (path.exists()) Yaml.default.decodeFromString(TokenFile.serializer(), path.readText()).tokens else emptyList()
        generation++
    }

    /** Creates a token and returns its plain value, which exists nowhere else afterwards. */
    @Synchronized
    fun issue(name: String, scopes: List<String>, createdBy: String, expiresAt: Instant?): String {
        require(tokens.none { it.name == name }) { "A token named $name already exists" }
        val token = ConfigFile.newToken()
        save(tokens + IssuedToken(name, digest(token), scopes.distinct(), Instant.now().toString(), createdBy, expiresAt?.toString()))
        return token
    }

    @Synchronized
    fun revoke(name: String): Boolean {
        if (tokens.none { it.name == name }) return false
        save(tokens.filterNot { it.name == name })
        return true
    }

    private fun save(updated: List<IssuedToken>) {
        Files.createDirectories(path.parent)
        val temporary = path.resolveSibling("${path.fileName}.tmp")
        temporary.writeText(HEADER + Yaml.default.encodeToString(TokenFile.serializer(), TokenFile(updated)) + "\n")
        Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        tokens = updated
        generation++
    }

    companion object {
        private const val HEADER = "# Tokens created with /worldapi token create. Only SHA-256 digests live here;\n" +
            "# the tokens themselves were shown once. Revoke with /worldapi token revoke <name>.\n"

        fun digest(token: String): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.toByteArray()))
    }
}
