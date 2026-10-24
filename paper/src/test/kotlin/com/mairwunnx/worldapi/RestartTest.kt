package com.mairwunnx.worldapi

import com.mairwunnx.worldapi.core.MainThread
import com.mairwunnx.worldapi.core.Restart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import org.bukkit.Server
import java.lang.reflect.Proxy
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RestartTest {
    private val calls = Collections.synchronizedList(mutableListOf<String>())

    private val server = Proxy.newProxyInstance(Server::class.java.classLoader, arrayOf(Server::class.java)) { _, method, args ->
        when (method.name) {
            "broadcast" -> calls += "say " + ((args[0] as Component) as TextComponent).content()
            "getWorlds" -> return@newProxyInstance emptyList<Any>()
            else -> calls += method.name
        }
        if (method.returnType == Integer.TYPE) 0 else null
    } as Server

    private fun restart() = Restart(server, MainThread.Immediate, CoroutineScope(SupervisorJob() + Dispatchers.Default), secondMillis = 20)

    @Test
    fun `a countdown warns, saves, and stops the server`() = runBlocking {
        val restart = restart()
        val status = restart.schedule(3, "new plugins")
        assertTrue(status.scheduled)
        assertEquals("new plugins", status.message)
        delay(400)
        assertEquals(
            listOf("say Server restarts in 3 seconds: new plugins", "say Server restarts in 2 seconds: new plugins", "say Server restarts in 1 second: new plugins", "say Server restarting now", "savePlayers", "shutdown"),
            calls.toList(),
        )
        assertFalse(restart.status().scheduled)
    }

    @Test
    fun `a cancelled restart never stops the server`() = runBlocking {
        val restart = restart()
        restart.schedule(30, null)
        delay(50)
        assertTrue(restart.cancel())
        assertFalse(restart.cancel())
        delay(700)
        assertTrue("shutdown" !in calls)
        assertEquals("say Restart cancelled", calls.last())
    }

    @Test
    fun `warnings read naturally`() {
        assertEquals("1 minute", Restart.duration(60))
        assertEquals("5 minutes", Restart.duration(300))
        assertEquals("90 seconds", Restart.duration(90))
        assertEquals("1 second", Restart.duration(1))
    }
}
