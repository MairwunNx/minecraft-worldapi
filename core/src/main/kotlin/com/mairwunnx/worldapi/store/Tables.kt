package com.mairwunnx.worldapi.store

import org.jetbrains.exposed.v1.core.Table

// The names match the SQLite file WorldAPI 1.x wrote, so it keeps working. Text columns that make up
// a key or an index are VARCHAR, since MySQL indexes text only up to a length.

object Events : Table("events") {
    val id = long("id").autoIncrement()
    val type = varchar("type", 64)
    val at = long("at")
    val playerUuid = varchar("player_uuid", 36).nullable()
    val playerName = text("player_name").nullable()
    val world = text("world").nullable()
    /** JSON; LONGTEXT on MySQL, whose TEXT stops at 64 KB. */
    val data = largeText("data")
    /** Recorded while the player was vanished: only admin clients read it. Added in 2.2. */
    val hidden = bool("hidden").default(false)

    override val primaryKey = PrimaryKey(id)

    init {
        index("events_type_at", false, type, at)
        index("events_player", false, playerUuid, id)
    }
}

object Sessions : Table("sessions") {
    val id = long("id").autoIncrement()
    val playerUuid = varchar("player_uuid", 36)
    val playerName = text("player_name")
    val joinedAt = long("joined_at")
    val leftAt = long("left_at").nullable()
    val closedByRestart = bool("closed_by_restart").default(false)
    /** The player joined vanished: only admin clients read it. Added in 2.2. */
    val hidden = bool("hidden").default(false)

    override val primaryKey = PrimaryKey(id)

    init {
        index("sessions_player", false, playerUuid, id)
    }
}

object Audit : Table("audit") {
    val id = long("id").autoIncrement()
    val at = long("at")
    val token = text("token")
    val method = text("method")
    val path = text("path")
    val target = text("target").nullable()
    val status = integer("status")
    val detail = text("detail").nullable()

    override val primaryKey = PrimaryKey(id)
}

object ExperienceGained : Table("experience") {
    val playerUuid = varchar("player_uuid", 36)
    val playerName = text("player_name")
    val gained = long("gained")

    override val primaryKey = PrimaryKey(playerUuid)
}

/** One row per player whose experience total started from their XpTotal, so a restart never adds it again. */
object ExperienceBaseline : Table("experience_baseline") {
    val playerUuid = varchar("player_uuid", 36)
    val xpTotal = long("xp_total")
    val takenAt = long("taken_at")

    override val primaryKey = PrimaryKey(playerUuid)
}

/** Animals each player has tamed: the pets in the world when WorldAPI first counted, and every one tamed since. Added in 2.4. */
object TamedAnimals : Table("tamed") {
    val playerUuid = varchar("player_uuid", 36)
    val playerName = text("player_name")
    val tamed = long("tamed")

    override val primaryKey = PrimaryKey(playerUuid)
}

/** One row once WorldAPI has counted the pets already in the world, so a restart never counts them again. */
object TamedSeed : Table("tamed_seed") {
    val id = integer("id")
    val takenAt = long("taken_at")
    val pets = long("pets")

    override val primaryKey = PrimaryKey(id)
}

/** Admin panel accounts. Added in 3.0. */
object Accounts : Table("accounts") {
    val name = varchar("name", 32)
    /** PBKDF2-HMAC-SHA256: `pbkdf2$<iterations>$<salt hex>$<hash hex>`. */
    val password = varchar("password", 256)
    /** Permissions and group names, comma-separated, as a token lists them. */
    val groups = text("groups")
    /** The Minecraft player whose code made the account, if any. */
    val playerUuid = varchar("player_uuid", 36).nullable()
    val createdAt = long("created_at")
    val createdBy = text("created_by")
    val lastLoginAt = long("last_login_at").nullable()

    override val primaryKey = PrimaryKey(name)
}

/** Signed-in panel sessions; only a SHA-256 of the cookie is kept. Added in 3.0. */
object AdminSessions : Table("admin_sessions") {
    val digest = varchar("digest", 64)
    val account = varchar("account", 32)
    val createdAt = long("created_at")
    val expiresAt = long("expires_at")
    val address = text("address").nullable()
    val userAgent = text("user_agent").nullable()

    override val primaryKey = PrimaryKey(digest)

    init {
        index("admin_sessions_account", false, account)
    }
}

/** Webhooks made in game, in the panel, or through the API; config.yml's own stay there. Added in 4.3. */
object WebhookRows : Table("webhooks") {
    val name = varchar("name", 32)
    /** The webhook as JSON, its URL and secrets included: WorldAPI needs them to post. */
    val data = largeText("data")
    val createdAt = long("created_at")
    val createdBy = text("created_by")

    override val primaryKey = PrimaryKey(name)
}

val TABLES = arrayOf(Events, Sessions, Audit, ExperienceGained, ExperienceBaseline, TamedAnimals, TamedSeed, Accounts, AdminSessions, WebhookRows)
