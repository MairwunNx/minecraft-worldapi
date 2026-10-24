package com.mairwunnx.worldapi.script

import com.mairwunnx.worldapi.script.Scripts
import com.mairwunnx.worldapi.api.ApiException
import com.mairwunnx.worldapi.api.badRequest
import com.mairwunnx.worldapi.api.conflict
import com.mairwunnx.worldapi.api.notFound
import com.mairwunnx.worldapi.config.WorldApiConfig
import com.mairwunnx.worldapi.core.BukkitMainThread
import com.mairwunnx.worldapi.events.ApiEvent
import com.mairwunnx.worldapi.events.EventBus
import com.mairwunnx.worldapi.events.Topic
import com.mairwunnx.worldapi.model.ScriptLogLine
import com.mairwunnx.worldapi.model.ScriptRunResult
import com.mairwunnx.worldapi.model.ScriptView
import com.mairwunnx.worldapi.script.api.WorldApiScript
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.bukkit.plugin.Plugin
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Logger
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Compiles, loads, runs, and unloads scripts. Each script gets its own class loader that sees
 * only [SCRIPT_PACKAGES]; unloading removes its listeners and tasks, closes the loader, and
 * deletes its classes, so nothing keeps the script's classes alive.
 */
@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class ScriptManager(
    private val plugin: Plugin,
    private val bus: EventBus,
    private val config: () -> WorldApiConfig,
    private val logger: Logger,
) : Scripts {
    private class Loaded(
        val id: String,
        val source: String,
        val className: String,
        val persistent: Boolean,
        val loader: URLClassLoader,
        val script: WorldApiScript,
        val context: ScriptContextImpl,
        val directory: Path,
        val loadedAt: Instant,
        val log: Log,
    )

    private class Log(private val size: () -> Int) {
        val lines = ArrayDeque<ScriptLogLine>()
        val errors = AtomicInteger()

        @Synchronized
        fun add(line: ScriptLogLine) {
            lines.addLast(line)
            while (lines.size > size()) lines.removeFirst()
        }

        @Synchronized
        fun tail(limit: Int) = lines.toList().takeLast(limit)
    }

    private val compiler = ScriptCompiler { config().scripts.blockedApis }
    private val scripts = ConcurrentHashMap<String, Loaded>()
    private val counter = AtomicLong()
    private val folder: Path by lazy { plugin.dataFolder.toPath().resolve("scripts") }
    private val build: Path by lazy { folder.resolve(".build") }
    private val main by lazy { BukkitMainThread(plugin) { config().scripts.runTimeoutMillis } }

    private fun requireEnabled() {
        if (!config().scripts.enabled) throw ApiException(HttpStatusCode.Forbidden, "scripts_disabled", "Scripts are off; set scripts.enabled in config.yml")
    }

    override suspend fun run(source: String, async: Boolean): ScriptRunResult {
        requireEnabled()
        val started = System.nanoTime()
        val output = mutableListOf<String>()
        val directory = build.resolve("run-${counter.incrementAndGet()}")
        val (loader, script) = load(source, directory, snippetAllowed = true)
        val context = ScriptContextImpl("run", plugin) { message -> synchronized(output) { output += message } }
        try {
            val timeout = config().scripts.runTimeoutMillis
            val value = try {
                if (async) {
                    withTimeout(timeout) { runInterruptible(Dispatchers.IO) { script.run(context) } }
                } else {
                    main.call { script.run(context) }
                }
            } catch (_: TimeoutCancellationException) {
                throw ApiException(HttpStatusCode.GatewayTimeout, "script_timeout", "The script ran longer than ${timeout} ms")
            } catch (error: ApiException) {
                throw error
            } catch (error: Throwable) {
                throw ApiException(HttpStatusCode.UnprocessableEntity, "script_failed", "The script threw ${describe(error)}")
            }
            val result = if (async) toJson(value) else main.call { toJson(value) }
            return ScriptRunResult(result, synchronized(output) { output.toList() }, (System.nanoTime() - started) / 1_000_000)
        } finally {
            // One-off scripts don't outlive the call, even if they registered something.
            context.close()
            loader.close()
            withContext(Dispatchers.IO) { directory.deleteRecursively() }
        }
    }

    override suspend fun load(id: String, source: String, persistent: Boolean, replace: Boolean): ScriptView {
        requireEnabled()
        if (!Regex("[a-z0-9_-]{1,40}").matches(id)) badRequest("id must be 1–40 lowercase letters, digits, dashes, or underscores")
        if (scripts.containsKey(id)) {
            if (!replace) conflict("script_exists", "A script \"$id\" is loaded; pass replace to swap it")
            unload(id, forget = false)
        }
        if (scripts.size >= config().scripts.maxLoaded) conflict("too_many_scripts", "${scripts.size} scripts are loaded; unload one first")
        val loaded = start(id, source, persistent)
        if (persistent) withContext(Dispatchers.IO) {
            Files.createDirectories(folder)
            folder.resolve("$id.java").writeText(source)
        }
        return view(loaded, withSource = false)
    }

    private suspend fun start(id: String, source: String, persistent: Boolean): Loaded {
        val directory = build.resolve("$id-${counter.incrementAndGet()}")
        val (loader, script) = load(source, directory, snippetAllowed = false)
        val log = Log { config().scripts.logLines }
        val context = ScriptContextImpl(id, plugin) { message -> log(id, log, message) }
        try {
            main.call { script.enable(context) }
        } catch (error: Throwable) {
            context.close()
            loader.close()
            withContext(Dispatchers.IO) { directory.deleteRecursively() }
            if (error is ApiException) throw error
            throw ApiException(HttpStatusCode.UnprocessableEntity, "script_failed", "enable() threw ${describe(error)}")
        }
        val loaded = Loaded(id, source, script.javaClass.name, persistent, loader, script, context, directory, Instant.now(), log)
        scripts[id] = loaded
        log(id, log, "loaded ${script.javaClass.name}")
        return loaded
    }

    private suspend fun load(source: String, directory: Path, snippetAllowed: Boolean): Pair<URLClassLoader, WorldApiScript> {
        if (source.length > 200_000) badRequest("The source is longer than 200000 characters")
        val snippet = snippetAllowed && ScriptCompiler.declaredClass(source) == null
        val compiled = withContext(Dispatchers.IO) {
            try {
                if (!snippet) return@withContext compiler.compile(source, directory)
                try {
                    compiler.compile(ScriptCompiler.wrapSnippet(source), directory)
                } catch (error: CompileError) {
                    // A snippet that doesn't end in return still means "run this".
                    if (error.diagnostics.none { "must return a result" in it }) throw error
                    compiler.compile(ScriptCompiler.wrapSnippet("$source\nreturn null;"), directory)
                }
            } catch (error: CompileError) {
                directory.deleteRecursively()
                val lines = if (snippet) error.diagnostics.map { shiftLine(it, ScriptCompiler.SNIPPET_OFFSET) } else error.diagnostics
                throw ApiException(HttpStatusCode.UnprocessableEntity, "compile_failed", lines.joinToString("\n"))
            }
        }
        val loader = URLClassLoader("worldapi-script", arrayOf(compiled.directory.toUri().toURL()), FilteringClassLoader(plugin.javaClass.classLoader))
        val instance = try {
            val type = loader.loadClass(compiled.mainClass)
            if (!WorldApiScript::class.java.isAssignableFrom(type)) badRequest("${compiled.mainClass} doesn't implement WorldApiScript")
            type.getDeclaredConstructor().newInstance() as WorldApiScript
        } catch (error: Throwable) {
            loader.close()
            withContext(Dispatchers.IO) { directory.deleteRecursively() }
            if (error is ApiException) throw error
            throw ApiException(HttpStatusCode.UnprocessableEntity, "script_failed", "Couldn't create ${compiled.mainClass}: ${describe(error)}")
        }
        return loader to instance
    }

    override suspend fun unload(id: String, forget: Boolean) {
        val loaded = scripts.remove(id) ?: notFound("script_not_found", "No script \"$id\"")
        stop(loaded)
        if (forget) withContext(Dispatchers.IO) { Files.deleteIfExists(folder.resolve("$id.java")) }
    }

    private suspend fun stop(loaded: Loaded) {
        runCatching { main.call { loaded.script.disable() } }.onFailure { log(loaded.id, loaded.log, "disable() threw ${describe(it)}") }
        main.call { loaded.context.close() }
        log(loaded.id, loaded.log, "unloaded")
        loaded.loader.close()
        withContext(Dispatchers.IO) { runCatching { loaded.directory.deleteRecursively() } }
    }

    override fun list(): List<ScriptView> = scripts.values.sortedBy { it.id }.map { view(it, withSource = false) }

    override fun get(id: String): ScriptView = view(scripts[id] ?: notFound("script_not_found", "No script \"$id\""), withSource = true)

    override fun logs(id: String, limit: Int): List<ScriptLogLine> = (scripts[id] ?: notFound("script_not_found", "No script \"$id\"")).log.tail(limit)

    /** Loads saved scripts after a restart; a broken one is logged and skipped. */
    suspend fun loadSaved() {
        if (!config().scripts.enabled || !folder.exists()) return
        withContext(Dispatchers.IO) { if (build.exists()) build.deleteRecursively() }
        for (file in folder.listDirectoryEntries("*.java")) {
            val id = file.nameWithoutExtension
            runCatching { start(id, file.readText(), persistent = true) }
                .onFailure { logger.warning("Script $id didn't load: ${(it as? ApiException)?.message ?: it}") }
        }
    }

    /** On plugin disable: the server thread is ours, so everything stops right here. */
    fun unloadAll() {
        for (loaded in scripts.values) {
            runCatching { loaded.script.disable() }
            loaded.context.close()
            loaded.loader.close()
        }
        scripts.clear()
    }

    private fun log(id: String, log: Log, message: String) {
        val line = ScriptLogLine(Instant.now().toString(), message)
        log.add(line)
        if (message.startsWith("error")) log.errors.incrementAndGet()
        logger.info("[script $id] $message")
        bus.stream(ApiEvent(Topic.SCRIPT_LOG, buildJsonObject {
            put("script", id)
            put("message", message)
        }))
    }

    private fun view(loaded: Loaded, withSource: Boolean) = ScriptView(
        id = loaded.id,
        className = loaded.className,
        persistent = loaded.persistent,
        loadedAt = loaded.loadedAt.toString(),
        listeners = loaded.context.listenerCount,
        tasks = loaded.context.taskCount,
        errors = loaded.log.errors.get(),
        source = if (withSource) loaded.source else null,
    )

    private fun describe(error: Throwable): String {
        val cause = generateSequence(error) { it.cause }.last()
        val at = cause.stackTrace.firstOrNull { it.className.startsWith("Snippet") || it.fileName?.endsWith(".java") == true }
        return "${cause.javaClass.name}: ${cause.message}" + (at?.let { " (line ${it.lineNumber})" } ?: "")
    }

    private fun shiftLine(diagnostic: String, offset: Int): String =
        Regex("^line (\\d+):").replace(diagnostic) { "line ${(it.groupValues[1].toLong() - offset).coerceAtLeast(1)}:" }

    companion object {
        /** Turns what a script returned into JSON: maps, lists, arrays, and primitives keep their shape. */
        fun toJson(value: Any?, depth: Int = 0): JsonElement = when {
            value == null -> JsonNull
            depth > 8 -> JsonPrimitive(value.toString())
            value is JsonElement -> value
            value is String -> JsonPrimitive(value)
            value is Number -> JsonPrimitive(value)
            value is Boolean -> JsonPrimitive(value)
            value is Char -> JsonPrimitive(value.toString())
            value is Enum<*> -> JsonPrimitive(value.name.lowercase())
            value is Map<*, *> -> JsonObject(value.entries.take(10_000).associate { it.key.toString() to toJson(it.value, depth + 1) })
            value is Iterable<*> -> JsonArray(value.take(10_000).map { toJson(it, depth + 1) })
            value is Array<*> -> JsonArray(value.take(10_000).map { toJson(it, depth + 1) })
            value is IntArray -> JsonArray(value.map(::JsonPrimitive))
            value is LongArray -> JsonArray(value.map(::JsonPrimitive))
            value is DoubleArray -> JsonArray(value.map(::JsonPrimitive))
            value is org.bukkit.Keyed -> JsonPrimitive(value.key.asString())
            value is net.kyori.adventure.text.Component -> JsonPrimitive(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(value))
            else -> JsonPrimitive(value.toString())
        }
    }
}

/** The parent of every script class loader: it passes on only the allowed packages. */
class FilteringClassLoader(private val delegate: ClassLoader) : ClassLoader("worldapi-script-api", null) {
    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        if (SCRIPT_PACKAGES.none { name.startsWith(it) }) throw ClassNotFoundException("Scripts can't use $name")
        return delegate.loadClass(name)
    }

    override fun getResource(name: String) = null
}
