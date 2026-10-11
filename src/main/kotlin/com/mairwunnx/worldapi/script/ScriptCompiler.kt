package com.mairwunnx.worldapi.script

import com.mairwunnx.worldapi.script.api.WorldApiScript
import org.eclipse.jdt.internal.compiler.tool.EclipseCompiler
import java.io.File
import java.io.StringWriter
import java.lang.classfile.ClassFile
import java.lang.classfile.constantpool.ClassEntry
import java.lang.classfile.constantpool.MemberRefEntry
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import javax.tools.Diagnostic
import javax.tools.DiagnosticCollector
import javax.tools.JavaFileObject
import javax.tools.StandardLocation
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes
import kotlin.io.path.relativeTo
import kotlin.io.path.walk

/** Packages a script may load at runtime: Java itself and the Paper API with what its signatures expose. */
val SCRIPT_PACKAGES = listOf(
    "java.", "org.bukkit.", "io.papermc.paper.", "com.destroystokyo.paper.", "org.spigotmc.", "net.md_5.bungee.api.",
    "net.kyori.", "com.google.common.", "com.mojang.brigadier.", "org.joml.", "org.jspecify.", "org.jetbrains.annotations.",
    "com.mairwunnx.worldapi.script.api.",
)

class CompileError(val diagnostics: List<String>) : RuntimeException(diagnostics.joinToString("\n"))

class Compiled(val directory: Path, val mainClass: String)

/**
 * Compiles a script with the Eclipse compiler, which runs on a JRE. The classpath holds only the
 * jars behind [SCRIPT_PACKAGES], so a reference to anything else fails here rather than later,
 * and the bytecode check rejects calls on the blocked list before any class loads.
 */
class ScriptCompiler(private val blocked: () -> List<String>) {
    private val classpath: List<File> by lazy {
        listOf(
            "org.bukkit.Bukkit", "net.kyori.adventure.text.Component", "net.kyori.adventure.key.Key", "net.kyori.examination.Examinable",
            "net.kyori.adventure.text.minimessage.MiniMessage", "net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer",
            "net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer", "net.kyori.adventure.text.serializer.gson.GsonComponentSerializer",
            "net.md_5.bungee.api.chat.BaseComponent", "com.google.common.collect.Multimap", "com.mojang.brigadier.Command",
            "org.joml.Vector3f", "org.jspecify.annotations.Nullable", "org.jetbrains.annotations.NotNull", WorldApiScript::class.java.name,
        ).mapNotNull { name ->
            runCatching { Class.forName(name, false, javaClass.classLoader).protectionDomain.codeSource.location.toURI() }.getOrNull()?.let { File(it) }
        }.distinct()
    }

    fun compile(source: String, into: Path): Compiled {
        val (pkg, name) = declaredClass(source) ?: throw CompileError(listOf("Declare a public class that implements WorldApiScript"))
        // ECJ reads sources from disk, so the source goes next to the classes it produces.
        val classes = Files.createDirectories(into.resolve("classes"))
        val file = Files.createDirectories(into.resolve("src").resolve(pkg.replace('.', '/'))).resolve("$name.java")
        Files.writeString(file, source)
        val compiler = EclipseCompiler()
        val diagnostics = DiagnosticCollector<JavaFileObject>()
        val files = compiler.getStandardFileManager(diagnostics, Locale.ENGLISH, Charsets.UTF_8)
        files.setLocation(StandardLocation.CLASS_PATH, classpath)
        files.setLocation(StandardLocation.CLASS_OUTPUT, listOf(classes.toFile()))
        val unit = files.getJavaFileObjects(file.toFile()).single()
        val output = StringWriter()
        val options = listOf("-source", "21", "-target", "21", "-encoding", "UTF-8", "-proc:none", "-g", "-parameters", "-nowarn")
        val ok = compiler.getTask(output, files, diagnostics, options, null, listOf(unit)).call()
        files.close()
        val errors = diagnostics.diagnostics.filter { it.kind == Diagnostic.Kind.ERROR }.map {
            "line ${it.lineNumber}: ${it.getMessage(Locale.ENGLISH)}"
        }
        if (!ok || errors.isNotEmpty()) throw CompileError(errors.ifEmpty { listOf(output.toString().ifBlank { "Compilation failed" }) })
        check(classes)
        return Compiled(classes, if (pkg.isEmpty()) name else "$pkg.$name")
    }

    /** Rejects class files that reference a blocked class or method. */
    private fun check(directory: Path) {
        val rules = blocked().map { rule -> rule.substringBefore('#') to rule.substringAfter('#', "").ifEmpty { null } }
        val problems = mutableSetOf<String>()
        directory.walk().filter { it.isRegularFile() && it.toString().endsWith(".class") }.forEach { file ->
            val model = ClassFile.of().parse(file.readBytes())
            for (entry in model.constantPool()) {
                val (owner, member) = when (entry) {
                    is MemberRefEntry -> entry.owner().asInternalName() to entry.name().stringValue()
                    is ClassEntry -> entry.asInternalName() to null
                    else -> continue
                }
                for ((prefix, method) in rules) {
                    val hit = owner.startsWith(prefix) && (method == null || (member != null && member.startsWith(method)))
                    if (hit) problems += "${file.relativeTo(directory)} uses ${owner.replace('/', '.')}${member?.let { "#$it" } ?: ""}"
                }
            }
        }
        if (problems.isNotEmpty()) throw CompileError(problems.sorted().map { "blocked: $it" })
    }

    companion object {
        private val packagePattern = Regex("""^\s*package\s+([\w.]+)\s*;""", RegexOption.MULTILINE)
        private val classPattern = Regex("""public\s+(?:final\s+)?class\s+(\w+)""")

        fun declaredClass(source: String): Pair<String, String>? {
            val name = classPattern.find(source)?.groupValues?.get(1) ?: return null
            return (packagePattern.find(source)?.groupValues?.get(1) ?: "") to name
        }

        private val SNIPPET_HEADER = """
            import org.bukkit.*;
            import org.bukkit.block.*;
            import org.bukkit.entity.*;
            import org.bukkit.inventory.*;
            import org.bukkit.potion.*;
            import org.bukkit.event.*;
            import java.util.*;
            import java.util.stream.*;
            import net.kyori.adventure.text.Component;
            import com.mairwunnx.worldapi.script.api.*;

            public final class Snippet implements WorldApiScript {
                @Override
                public Object run(ScriptContext ctx) throws Exception {
                    final Server server = ctx.server();
        """.trimIndent() + "\n"

        /** Lines the wrapper adds before a snippet, to point compile errors at the caller's own lines. */
        val SNIPPET_OFFSET = SNIPPET_HEADER.count { it == '\n' }

        /**
         * Lets a caller send only the body of `run`, such as `return server.getOnlinePlayers().size();`.
         * Common imports come along, and `ctx` and `server` are in scope.
         */
        fun wrapSnippet(body: String): String = SNIPPET_HEADER + body + "\n    }\n}\n"
    }
}
