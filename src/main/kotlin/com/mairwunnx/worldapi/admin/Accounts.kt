package com.mairwunnx.worldapi.admin

import com.mairwunnx.worldapi.store.Accounts as AccountTable
import com.mairwunnx.worldapi.store.AdminSessions
import com.mairwunnx.worldapi.store.Store
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap

/**
 * Admin panel accounts, their sessions, and the one-time codes that create them. A session is a
 * random cookie; the database keeps only its SHA-256, so a stolen database signs nobody in.
 */
class AdminAccounts(private val store: Store, private val clock: () -> Long = System::currentTimeMillis) {
    data class Account(
        val name: String,
        val groups: List<String>,
        val playerUuid: String?,
        val createdAt: Long,
        val createdBy: String,
        val lastLoginAt: Long?,
    )

    /** A code from /worldapi admin or an invite: who it's for and what they get. Single use. */
    data class Code(val name: String?, val playerUuid: String?, val groups: List<String>, val createdBy: String, val expiresAt: Long, val reset: Boolean = false)

    private val codes = ConcurrentHashMap<String, Code>()
    private val random = SecureRandom()

    suspend fun list(): List<Account> = store.read { AccountTable.selectAll().orderBy(AccountTable.name, SortOrder.ASC).map { it.account() } }

    suspend fun count(): Long = store.read { AccountTable.selectAll().count() }

    suspend fun find(name: String): Account? = store.read { AccountTable.selectAll().where { AccountTable.name eq name }.firstOrNull()?.account() }

    /** The account when the password matches. Unknown names cost the same time as known ones. */
    suspend fun verify(name: String, password: String): Account? {
        val row = store.read { AccountTable.selectAll().where { AccountTable.name eq name }.firstOrNull()?.let { it.account() to it[AccountTable.password] } }
        val ok = Passwords.verify(password, row?.second ?: Passwords.decoy)
        return row?.first?.takeIf { ok }
    }

    suspend fun create(name: String, password: String, groups: List<String>, playerUuid: String?, createdBy: String): Account {
        val hash = Passwords.hash(password)
        val now = clock()
        store.write {
            AccountTable.insert {
                it[AccountTable.name] = name
                it[AccountTable.password] = hash
                it[AccountTable.groups] = groups.joinToString(",")
                it[AccountTable.playerUuid] = playerUuid
                it[createdAt] = now
                it[AccountTable.createdBy] = createdBy
            }
        }
        return find(name) ?: error("The account $name wasn't saved")
    }

    suspend fun setGroups(name: String, groups: List<String>) {
        store.write { AccountTable.update({ AccountTable.name eq name }) { it[AccountTable.groups] = groups.joinToString(",") } }
        store.read { }
    }

    /** A new password signs the account out everywhere. */
    suspend fun setPassword(name: String, password: String) {
        val hash = Passwords.hash(password)
        store.write {
            AccountTable.update({ AccountTable.name eq name }) { it[AccountTable.password] = hash }
            AdminSessions.deleteWhere { account eq name }
        }
        store.read { }
    }

    suspend fun delete(name: String) {
        store.write {
            AdminSessions.deleteWhere { account eq name }
            AccountTable.deleteWhere { AccountTable.name eq name }
        }
        store.read { }
    }

    // ---- Sessions ----

    /** Starts a session and returns the cookie value, which exists nowhere else afterwards. */
    suspend fun startSession(name: String, address: String?, userAgent: String?): String {
        val token = HexFormat.of().formatHex(ByteArray(32).also(random::nextBytes))
        val now = clock()
        store.write {
            AdminSessions.insert {
                it[digest] = digest(token)
                it[account] = name
                it[createdAt] = now
                it[expiresAt] = now + SESSION_MILLIS
                it[AdminSessions.address] = address
                it[AdminSessions.userAgent] = userAgent?.take(256)
            }
            AccountTable.update({ AccountTable.name eq name }) { it[lastLoginAt] = now }
            AdminSessions.deleteWhere { expiresAt less now }
        }
        store.read { }
        return token
    }

    /**
     * The account behind a session cookie, or null when it's unknown or expired. A session in use
     * slides forward, at most once an hour, so an active admin stays signed in.
     */
    suspend fun session(token: String): Account? {
        val now = clock()
        val key = digest(token)
        val (name, expires) = store.read {
            AdminSessions.selectAll().where { (AdminSessions.digest eq key) }.firstOrNull()?.let { it[AdminSessions.account] to it[AdminSessions.expiresAt] }
        } ?: return null
        if (expires <= now) return null
        if (now + SESSION_MILLIS - expires > 3_600_000) {
            store.write { AdminSessions.update({ (AdminSessions.digest eq key) and (AdminSessions.account eq name) }) { it[expiresAt] = now + SESSION_MILLIS } }
        }
        return find(name)
    }

    fun endSession(token: String) {
        val key = digest(token)
        store.write { AdminSessions.deleteWhere { digest eq key } }
    }

    // ---- Codes ----

    /** A code like `K7QM-2XDP`, good for [CODE_MILLIS]. [name] null lets the person pick a name at setup. */
    /** With [reset], the code sets a new password for the existing account [name] instead of creating one. */
    fun issueCode(name: String?, playerUuid: String?, groups: List<String>, createdBy: String, reset: Boolean = false): String {
        val now = clock()
        codes.values.removeIf { it.expiresAt <= now }
        val code = (1..8).map { CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)] }.joinToString("").let { it.take(4) + "-" + it.drop(4) }
        codes[code] = Code(name, playerUuid, groups, createdBy, now + CODE_MILLIS, reset)
        return code
    }

    /** Takes a code if it's valid; it can't be used again either way. */
    fun takeCode(code: String): Code? {
        val normal = code.trim().uppercase().replace(" ", "").let { if ('-' !in it && it.length == 8) it.take(4) + "-" + it.drop(4) else it }
        return codes.remove(normal)?.takeIf { it.expiresAt > clock() }
    }

    private fun org.jetbrains.exposed.v1.core.ResultRow.account() = Account(
        this[AccountTable.name],
        this[AccountTable.groups].split(',').map { it.trim() }.filter { it.isNotEmpty() },
        this[AccountTable.playerUuid],
        this[AccountTable.createdAt],
        this[AccountTable.createdBy],
        this[AccountTable.lastLoginAt],
    )

    companion object {
        const val SESSION_MILLIS = 7L * 24 * 3_600_000
        const val CODE_MILLIS = 30L * 60_000
        // No 0/O, 1/I/L: a code read off a game screen is typed by hand.
        private const val CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
        val NAME = Regex("[A-Za-z0-9_.-]{3,32}")

        fun digest(token: String): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.toByteArray()))
    }
}
