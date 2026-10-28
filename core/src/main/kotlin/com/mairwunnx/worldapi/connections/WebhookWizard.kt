package com.mairwunnx.worldapi.connections

import com.mairwunnx.worldapi.api.ApiException
import com.mairwunnx.worldapi.api.Scope
import com.mairwunnx.worldapi.config.WEBHOOK_LANGUAGES
import com.mairwunnx.worldapi.config.Webhook
import com.mairwunnx.worldapi.config.WebhookFormat
import com.mairwunnx.worldapi.connections.ChatLine.Button
import com.mairwunnx.worldapi.connections.ChatLine.Buttons
import com.mairwunnx.worldapi.connections.ChatLine.Text
import com.mairwunnx.worldapi.connections.ChatLine.Tone
import com.mairwunnx.worldapi.core.WorldApi
import com.mairwunnx.worldapi.model.WebhookRequest
import com.mairwunnx.worldapi.webhooks.DEFAULT_WEBHOOK_PERMISSIONS
import com.mairwunnx.worldapi.webhooks.Webhooks
import com.mairwunnx.worldapi.webhooks.buildWebhook
import com.mairwunnx.worldapi.webhooks.createWebhook
import com.mairwunnx.worldapi.webhooks.result
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** A window with one text field that asks for a secret; the platform shows it as the game's dialog. */
data class SecretPrompt(val nonce: String, val title: String, val text: String, val label: String, val button: String)

/**
 * `/worldapi webhook`: an operator connects Discord, Slack, Telegram, Teams, or Google Chat from the game, by buttons in
 * chat. The webhook's URL or bot token is a secret: a command would land in the server's log, and chat in other plugins'
 * hands, so the game's dialog asks for it ([ask]), and its answer comes back as a click action that no chat or command
 * event sees ([answer]). Operators hold every permission, as `/worldapi` makes tokens with any, so what they make goes
 * through the same checks as the API with all of them.
 */
class WebhookWizard(
    private val api: () -> WorldApi,
    private val scope: CoroutineScope,
    private val reply: (UUID, List<ChatLine>) -> Unit,
    private val ask: (UUID, SecretPrompt) -> Unit,
) {
    private enum class Step { TOPICS, LANGUAGE, SECRET, CHAT }

    private class Draft(val format: WebhookFormat, var step: Step) {
        /** Each step starts the 10 minutes again, so a slow trip to Discord's settings doesn't lose the draft. */
        var touched: Long = System.currentTimeMillis()
        var topics: List<String> = emptyList()
        var permissions: List<String> = DEFAULT_WEBHOOK_PERMISSIONS
        var language = "en"
        var secret: String? = null

        /** Ties a dialog's answer to this draft, so an old window can't fill it. */
        var nonce: String = UUID.randomUUID().toString()
    }

    private val drafts = ConcurrentHashMap<UUID, Draft>()

    /** Ready-made sets of topics; `chat` also needs chat:read. */
    private val presets = linkedMapOf(
        "all" to ("Everything notable" to listOf("*")),
        "players" to ("Joins, leaves, deaths, advancements" to listOf("player.join", "player.leave", "player.death", "player.advancement")),
        "server" to ("Server starts and stops, raids, bosses" to listOf("server.status", "raid.start", "raid.finish", "entity.boss_killed", "world.night_skipped")),
        "chat" to ("Chat" to listOf("player.chat")),
    )

    private val languageNames = mapOf("en" to "English", "ru" to "Русский", "es" to "Español", "pt" to "Português", "de" to "Deutsch")

    /** Runs `/worldapi webhook [args]` for [player]; the answer goes back through [reply]. */
    fun command(player: UUID, name: String, args: List<String>) {
        val lines = runCatching { handle(player, name, args) }.getOrElse { listOf(Text(it.message ?: "Something went wrong", Tone.BAD)) }
        if (lines.isNotEmpty()) reply(player, lines)
    }

    private fun handle(player: UUID, name: String, args: List<String>): List<ChatLine> = when (args.firstOrNull()) {
        null, "list" -> overview()
        "new" -> args.getOrNull(1)?.let { start(player, it) } ?: formats()
        "topics" -> topics(player, args.getOrNull(1))
        "language" -> language(player, args.getOrNull(1))
        "ask" -> prompt(player, draft(player, Step.SECRET))
        "chats" -> { findChats(player); emptyList() }
        "chat" -> { chooseChat(player, name, args.getOrNull(1)); emptyList() }
        "cancel" -> { drafts.remove(player); listOf(Text("Cancelled.", Tone.HINT)) }
        "test" -> { test(player, args.getOrNull(1)); emptyList() }
        "pause", "resume" -> { toggle(player, args.getOrNull(1), args[0] == "resume"); emptyList() }
        "delete" -> delete(player, args.getOrNull(1), args.getOrNull(2) == "confirm")
        else -> listOf(Text("Unknown step; start over with /worldapi webhook", Tone.BAD))
    }

    private fun overview(): List<ChatLine> {
        val webhooks = api().webhooks
        if (!api().config.webhooks.enabled) return listOf(Text("Webhooks are off in config.yml (webhooks.enabled).", Tone.BAD))
        val hooks = webhooks.all()
        return buildList {
            add(Text(if (hooks.isEmpty()) "No webhooks yet. Send events to Discord, Slack, Telegram, Teams, or Google Chat:" else "Webhooks:"))
            hooks.forEach { entry ->
                val hook = entry.hook
                add(Text("${hook.name} · ${hook.format.title} · ${hook.topics.joinToString()} · ${hook.language}" + if (hook.enabled) "" else " · paused"))
                if (!entry.fromConfig) {
                    add(Buttons(listOf(
                        Button("[Test]", "Send it a test message", run = "/worldapi webhook test ${hook.name}"),
                        if (hook.enabled) Button("[Pause]", "Stop sending, keep the settings", run = "/worldapi webhook pause ${hook.name}")
                        else Button("[Resume]", "Send again", run = "/worldapi webhook resume ${hook.name}"),
                        Button("[Delete]", "Remove it", run = "/worldapi webhook delete ${hook.name}"),
                    )))
                }
            }
            add(Buttons(listOf(Button("[+ New webhook]", "Connect a chat service", run = "/worldapi webhook new"))))
        }
    }

    private fun formats() = listOf(
        Text("Where should events go?"),
        Buttons(WebhookFormat.entries.filter { it != WebhookFormat.JSON }.map { Button("[${it.title}]", "Post to ${it.title}", run = "/worldapi webhook new ${it.id}") }),
    )

    private fun start(player: UUID, id: String): List<ChatLine> {
        val format = WebhookFormat.of(id)?.takeIf { it != WebhookFormat.JSON } ?: return listOf(Text("Unknown service \"$id\"", Tone.BAD))
        drafts[player] = Draft(format, Step.TOPICS)
        return listOf(
            Text("${format.title}: which events? You can change them later in the panel."),
            Buttons(presets.map { (key, value) -> Button("[${value.first}]", value.second.joinToString(), run = "/worldapi webhook topics $key") }),
        )
    }

    private fun topics(player: UUID, key: String?): List<ChatLine> {
        val draft = draft(player, Step.TOPICS)
        val preset = presets[key] ?: return listOf(Text("Pick one of the buttons above", Tone.BAD))
        draft.topics = preset.second
        if (key == "chat") draft.permissions = DEFAULT_WEBHOOK_PERMISSIONS + "chat:read"
        draft.step = Step.LANGUAGE
        return buildList {
            if (key == "chat" && !api().config.events.chat) add(Text("Chat reaches webhooks only with events.chat on in config.yml.", Tone.HINT))
            add(Text("Which language should the messages be in?"))
            add(Buttons(WEBHOOK_LANGUAGES.map { Button("[${languageNames[it]}]", it, run = "/worldapi webhook language $it") }))
        }
    }

    private fun language(player: UUID, code: String?): List<ChatLine> {
        val draft = draft(player, Step.LANGUAGE)
        if (code !in WEBHOOK_LANGUAGES) return listOf(Text("Pick one of the buttons above", Tone.BAD))
        draft.language = code!!
        draft.step = Step.SECRET
        return prompt(player, draft)
    }

    /** Opens the window that asks for the URL or the bot token. */
    private fun prompt(player: UUID, draft: Draft): List<ChatLine> {
        draft.nonce = UUID.randomUUID().toString()
        val (what, where) = when (draft.format) {
            WebhookFormat.DISCORD -> "Webhook URL" to "Discord: channel settings → Integrations → Webhooks → New Webhook → Copy Webhook URL."
            WebhookFormat.SLACK -> "Webhook URL" to "Slack: api.slack.com/apps → your app → Incoming Webhooks → Add New Webhook. Mattermost and Rocket.Chat take this format too."
            WebhookFormat.TELEGRAM -> "Bot token" to "Telegram: write /newbot to @BotFather; it answers with the bot's token, like 123456:ABC-DEF…"
            WebhookFormat.TEAMS -> "Workflow URL" to "Teams: the channel → Workflows → “Post to a channel when a webhook request is received”, then copy its URL."
            WebhookFormat.GOOGLE_CHAT -> "Webhook URL" to "Google Chat: the space → Apps & integrations → Webhooks → Add."
            WebhookFormat.JSON -> "URL" to "Any HTTPS address that takes JSON."
        }
        ask(player, SecretPrompt(draft.nonce, "${draft.format.title} for WorldAPI", "$where Paste it here: it goes straight to WorldAPI, not into chat or the log.", what, "Connect"))
        return listOf(
            Text("A window asks for the ${what.lowercase()}. Paste it there.", Tone.GOOD),
            Buttons(listOf(Button("[Open it again]", "If you closed it", run = "/worldapi webhook ask"), Button("[Cancel]", "Stop here", run = "/worldapi webhook cancel"))),
        )
    }

    /** What [player] typed into the window with [nonce]. Any thread: the rest runs off it. */
    fun answer(player: UUID, name: String, nonce: String, text: String) {
        val draft = drafts[player]?.takeIf { it.step == Step.SECRET && it.nonce == nonce && !expired(player, it) }
            ?: return reply(player, listOf(Text("That window is out of date; start over with /worldapi webhook new", Tone.BAD)))
        draft.touched = System.currentTimeMillis()
        val secret = text.trim()
        val again = Buttons(listOf(Button("[Try again]", "Open the window", run = "/worldapi webhook ask")))
        if (draft.format == WebhookFormat.TELEGRAM) {
            if (!Webhook.TELEGRAM_TOKEN.matches(secret)) return reply(player, listOf(Text("That isn't a bot token like 123456:ABC-DEF…", Tone.BAD), again))
            draft.secret = secret
            draft.step = Step.CHAT
            return reply(player, listOf(
                Text("Got it. Now write /start to the bot in Telegram, or add it to your group or channel, then press:", Tone.GOOD),
                Buttons(listOf(Button("[Find chats]", "Ask Telegram where the bot can write", run = "/worldapi webhook chats"), Button("[Cancel]", "Stop here", run = "/worldapi webhook cancel"))),
            ))
        }
        if (!secret.startsWith("https://") || secret.any(Char::isWhitespace)) {
            return reply(player, listOf(Text("That isn't a URL starting with https://.", Tone.BAD), again))
        }
        draft.secret = secret
        create(player, name, draft, url = secret)
    }

    private fun findChats(player: UUID) {
        val draft = draft(player, Step.CHAT)
        scope.launch {
            val lines = runCatching { api().webhooks.telegramChats(draft.secret!!) }.fold({ chats ->
                if (chats.isEmpty()) {
                    listOf(Text("The bot has no chats yet. Write /start to it, or add it to a group, then try again.", Tone.HINT),
                        Buttons(listOf(Button("[Find chats]", "Ask again", run = "/worldapi webhook chats"))))
                } else {
                    // Anyone may write to a bot, so the type shows on the button: a private chat is one person's.
                    val sorted = chats.sortedBy { if (it.type == "private") 1 else 0 }.take(8)
                    listOf(
                        Text("Where should it post? Pick your own group or channel: anyone who finds the bot can write to it too."),
                        Buttons(sorted.map { Button("[${it.title} · ${it.type}]", "Chat ${it.id}", run = "/worldapi webhook chat ${it.id}") }),
                    )
                }
            }, { listOf(Text(it.message ?: "Telegram didn't answer", Tone.BAD)) })
            reply(player, lines)
        }
    }

    private fun chooseChat(player: UUID, name: String, chatId: String?) {
        val draft = draft(player, Step.CHAT)
        if (chatId == null || !Webhook.TELEGRAM_CHAT.matches(chatId)) throw IllegalArgumentException("Pick one of the buttons above")
        create(player, name, draft, botToken = draft.secret, chatId = chatId)
    }

    private fun create(player: UUID, name: String, draft: Draft, url: String? = null, botToken: String? = null, chatId: String? = null) {
        drafts.remove(player)
        scope.launch {
            val api = api()
            val hookName = freeName(api.webhooks, draft.format.id)
            val request = WebhookRequest(hookName, draft.format.id, url, botToken, chatId, draft.topics, draft.permissions, draft.language)
            val lines = runCatching { api.createWebhook(request, "player:$name", Scope.entries.toSet()) }.fold({ created ->
                val test = created.test
                listOf(
                    Text("Webhook ${created.webhook.name} is ready.", Tone.GOOD),
                    if (test?.ok == true) Text("A test message went out; check ${draft.format.title}.", Tone.GOOD)
                    else Text("The test message didn't arrive (${test?.problem}). Check the URL, then test again or delete it.", Tone.BAD),
                    Buttons(listOf(Button("[All webhooks]", "List them", run = "/worldapi webhook"))),
                )
            }, { listOf(Text((it as? ApiException)?.message ?: it.message ?: "Couldn't make the webhook", Tone.BAD)) })
            reply(player, lines)
        }
    }

    private fun test(player: UUID, name: String?) {
        val entry = api().webhooks.all().firstOrNull { it.hook.name == name } ?: throw IllegalArgumentException("No webhook $name")
        scope.launch {
            val outcome = result(api().webhooks.test(entry.hook, entry.fromConfig))
            reply(player, listOf(if (outcome.ok) Text("Sent; check ${entry.hook.format.title}.", Tone.GOOD) else Text("It didn't arrive: ${outcome.problem}", Tone.BAD)))
        }
    }

    private fun toggle(player: UUID, name: String?, on: Boolean) {
        val store = api().webhooks.store ?: throw IllegalStateException("Webhooks can't be saved here")
        val stored = store.find(name ?: "") ?: throw IllegalArgumentException("No webhook $name made here; config.yml's change there")
        scope.launch {
            store.replace(buildWebhook { stored.hook.copy(enabled = on) })
            reply(player, listOf(Text(if (on) "${stored.hook.name} sends again." else "${stored.hook.name} is paused.", Tone.GOOD)))
        }
    }

    private fun delete(player: UUID, name: String?, confirmed: Boolean): List<ChatLine> {
        val store = api().webhooks.store ?: throw IllegalStateException("Webhooks can't be saved here")
        val stored = store.find(name ?: "") ?: throw IllegalArgumentException("No webhook $name made here; config.yml's change there")
        if (!confirmed) {
            return listOf(Text("Delete ${stored.hook.name}?"), Buttons(listOf(Button("[Yes, delete]", "It stops at once", run = "/worldapi webhook delete ${stored.hook.name} confirm"))))
        }
        scope.launch {
            store.remove(stored.hook.name)
            reply(player, listOf(Text("${stored.hook.name} is deleted.", Tone.GOOD)))
        }
        return emptyList()
    }

    private fun draft(player: UUID, step: Step): Draft {
        val draft = drafts[player]?.takeIf { !expired(player, it) } ?: throw IllegalStateException("Start with /worldapi webhook new")
        if (draft.step != step) throw IllegalStateException("Finish the step above first, or start over with /worldapi webhook new")
        draft.touched = System.currentTimeMillis()
        return draft
    }

    private fun expired(player: UUID, draft: Draft): Boolean {
        if (System.currentTimeMillis() - draft.touched < 10 * 60_000) return false
        drafts.remove(player)
        return true
    }

    private fun freeName(webhooks: Webhooks, base: String): String {
        val taken = webhooks.all().map { it.hook.name }.toSet()
        return generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base-$it" }.first { it !in taken }
    }
}
