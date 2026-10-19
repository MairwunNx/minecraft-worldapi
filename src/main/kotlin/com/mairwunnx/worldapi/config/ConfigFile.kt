package com.mairwunnx.worldapi.config

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.mairwunnx.worldapi.api.Cidr
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.HexFormat
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** Reads config.yml, writing the commented default with a fresh admin token on first start. */
object ConfigFile {
    private const val TOKEN_PLACEHOLDER = "{generated}"

    // Strict mode turns a typo in a key into an error instead of a silently ignored setting.
    private val yaml = Yaml(configuration = YamlConfiguration(strictMode = true))

    /** Returns the config and whether this call created the file. */
    fun load(path: Path): Pair<WorldApiConfig, Boolean> {
        val created = !path.exists()
        if (created) {
            Files.createDirectories(path.parent)
            path.writeText(defaultText().replace(TOKEN_PLACEHOLDER, newToken()))
        }
        return parse(path.readText()) to created
    }

    fun parse(text: String): WorldApiConfig = validate(yaml.decodeFromString(WorldApiConfig.serializer(), text))

    fun defaultText(): String =
        ConfigFile::class.java.getResourceAsStream("/config.yml")!!.use { it.readBytes().decodeToString() }

    fun newToken(): String = HexFormat.of().formatHex(ByteArray(32).also(SecureRandom()::nextBytes))

    private fun validate(config: WorldApiConfig): WorldApiConfig {
        val problems = buildList {
            if (config.http.port !in 1..65_535) add("http.port must be between 1 and 65535")
            if (!config.http.basePath.startsWith("/") || config.http.basePath.endsWith("/")) {
                add("http.basePath must start with / and not end with one")
            }
            config.auth.tokens.groupBy { it.name }.filter { it.value.size > 1 }.keys.forEach {
                add("auth.tokens: the name \"$it\" appears more than once")
            }
            runCatching { config.auth.resolver.validate() }.onFailure { add("auth.groups: ${it.message}") }
            config.auth.tokens.forEach { token ->
                token.scopes.filterNot(config.auth.resolver::contains).forEach { add("auth.tokens.${token.name}: \"$it\" is neither a permission nor a group") }
            }
            config.auth.tokens.filter { it.token.length < 16 || it.token == TOKEN_PLACEHOLDER }.forEach {
                add("auth.tokens.${it.name}: the token must be at least 16 characters")
            }
            val limits = listOf(config.rateLimit.anonymous, config.rateLimit.tokens) +
                config.auth.tokens.mapNotNull { it.rateLimit }
            if (limits.any { it.requestsPerMinute <= 0 || it.burst <= 0 }) add("rate limits must be positive")
            if (config.websocket.heartbeatSeconds <= 0) add("websocket.heartbeatSeconds must be positive")
            if (config.websocket.queueSize <= 0) add("websocket.queueSize must be positive")
            if (config.websocket.maxClientsPerClient <= 0) add("websocket.maxClientsPerClient must be positive")
            if (config.http.maxBodyBytes < 1024) add("http.maxBodyBytes must be at least 1024")
            config.http.trustedProxies.filter { Cidr.parse(it) == null }.forEach { add("http.trustedProxies: \"$it\" isn't an address or range") }
            if (config.limits.maxPageSize <= 0) add("limits.maxPageSize must be positive")
            if (config.database.poolSize <= 0) add("database.poolSize must be positive")
            config.database.port?.let { if (it !in 1..65_535) add("database.port must be between 1 and 65535") }
            if (config.database.type == DatabaseType.SQLITE && config.database.file.isBlank()) add("database.file must not be empty")
        }
        require(problems.isEmpty()) { problems.joinToString("; ") }
        return config
    }
}
