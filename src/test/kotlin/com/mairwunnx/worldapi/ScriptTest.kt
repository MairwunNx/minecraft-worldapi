package com.mairwunnx.worldapi

import com.mairwunnx.worldapi.script.CompileError
import com.mairwunnx.worldapi.script.FilteringClassLoader
import com.mairwunnx.worldapi.script.ScriptCompiler
import com.mairwunnx.worldapi.script.ScriptManager
import com.mairwunnx.worldapi.config.Scripts
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ScriptTest {
    private val compiler = ScriptCompiler { Scripts().blockedApis }

    @Test
    fun `snippets compile against the Paper API`() {
        val dir = Files.createTempDirectory("script")
        val compiled = compiler.compile(ScriptCompiler.wrapSnippet("return server.getOnlinePlayers().size() + Material.STONE.name().length();"), dir)
        assertEquals("Snippet", compiled.mainClass)
        assertTrue(dir.resolve("classes/Snippet.class").exists())
    }

    @Test
    fun `a full class with a package compiles`() {
        val source = """
            package demo;
            import com.mairwunnx.worldapi.script.api.*;
            import org.bukkit.event.player.PlayerJoinEvent;
            public class Greeter implements WorldApiScript {
                @Override public void enable(ScriptContext ctx) {
                    ctx.on(PlayerJoinEvent.class, e -> ctx.log("hi " + e.getPlayer().getName()));
                }
            }
        """.trimIndent()
        assertEquals("demo.Greeter", compiler.compile(source, Files.createTempDirectory("script")).mainClass)
    }

    @Test
    fun `blocked calls fail before anything loads`() {
        val error = assertFailsWith<CompileError> {
            compiler.compile(ScriptCompiler.wrapSnippet("return new ProcessBuilder(\"ls\").start().pid();"), Files.createTempDirectory("script"))
        }
        assertTrue(error.diagnostics.any { "java.lang.ProcessBuilder" in it }, error.diagnostics.toString())
        assertFailsWith<CompileError> { compiler.compile(ScriptCompiler.wrapSnippet("System.exit(1); return null;"), Files.createTempDirectory("script")) }
        assertFailsWith<CompileError> { compiler.compile(ScriptCompiler.wrapSnippet("return java.nio.file.Files.readString(java.nio.file.Path.of(\"x\"));"), Files.createTempDirectory("script")) }
        // Lambdas use java.lang.invoke under the hood; they must still work.
        compiler.compile(ScriptCompiler.wrapSnippet("Runnable r = () -> {}; r.run(); return 1;"), Files.createTempDirectory("script"))
    }

    @Test
    fun `compile errors come back as lines`() {
        val error = assertFailsWith<CompileError> { compiler.compile(ScriptCompiler.wrapSnippet("return nope;"), Files.createTempDirectory("script")) }
        assertTrue(error.diagnostics.single().startsWith("line ${ScriptCompiler.SNIPPET_OFFSET + 1}:"), error.diagnostics.toString())
        assertFailsWith<CompileError> { compiler.compile("class NotPublic {}", Files.createTempDirectory("script")) }
    }

    @Test
    fun `scripts see only allowed packages`() {
        val loader = FilteringClassLoader(javaClass.classLoader)
        assertEquals(org.bukkit.Bukkit::class.java, loader.loadClass("org.bukkit.Bukkit"))
        assertFailsWith<ClassNotFoundException> { loader.loadClass("com.mairwunnx.worldapi.WorldApiPlugin") }
        assertFailsWith<ClassNotFoundException> { loader.loadClass("kotlin.Unit") }
    }

    @Test
    fun `an unloaded script can be garbage collected`() {
        val source = "public class Answer implements com.mairwunnx.worldapi.script.api.WorldApiScript { public Object run(com.mairwunnx.worldapi.script.api.ScriptContext ctx) { return 42; } }"
        val compiled = compiler.compile(source, Files.createTempDirectory("script"))
        val reference = loadAndRun(compiled)
        repeat(50) {
            if (reference.get() == null) return
            System.gc()
            Thread.sleep(20)
        }
        error("The script's class loader is still reachable after closing it")
    }

    // A separate function, so no local variable keeps the loader alive in the test's frame.
    private fun loadAndRun(compiled: com.mairwunnx.worldapi.script.Compiled): java.lang.ref.WeakReference<ClassLoader> {
        val loader = java.net.URLClassLoader(arrayOf(compiled.directory.toUri().toURL()), FilteringClassLoader(javaClass.classLoader))
        val script = loader.loadClass(compiled.mainClass).getDeclaredConstructor().newInstance() as com.mairwunnx.worldapi.script.api.WorldApiScript
        assertEquals(42, script.run(null))
        loader.close()
        return java.lang.ref.WeakReference(loader)
    }

    @Test
    fun `results turn into JSON`() {
        assertEquals(JsonPrimitive(3), ScriptManager.toJson(3))
        assertEquals(JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive(true))), ScriptManager.toJson(listOf("a", true)))
        assertEquals(buildJsonObject { put("k", 1.5) }, ScriptManager.toJson(mapOf("k" to 1.5)))
        assertEquals(JsonPrimitive("new"), ScriptManager.toJson(Thread.State.NEW))
    }
}
