package com.mairwunnx.worldapi.core

import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.LogEvent
import org.apache.logging.log4j.core.Logger
import org.apache.logging.log4j.core.appender.AbstractAppender
import org.apache.logging.log4j.core.config.Property

/**
 * Copies every line the server logs into a [ConsoleLog]. Paper and the game log through Log4j, so
 * hooking its root logger sees the console as an operator does, plugins, mods, and chat included.
 */
class ConsoleAppender private constructor(private val log: ConsoleLog) :
    AbstractAppender("WorldAPI-console", null, null, true, Property.EMPTY_ARRAY) {

    override fun append(event: LogEvent) {
        val message = event.message?.formattedMessage ?: return
        val thrown = event.thrown?.let { "\n" + it.toString() } ?: ""
        log.add(event.timeMillis, event.level.name(), event.loggerName, message + thrown, event.threadName)
    }

    companion object {
        /** Starts copying into [log]; the returned function stops it. */
        fun install(log: ConsoleLog): () -> Unit {
            val root = LogManager.getRootLogger() as Logger
            val appender = ConsoleAppender(log).also { it.start() }
            root.addAppender(appender)
            return {
                root.removeAppender(appender)
                appender.stop()
            }
        }
    }
}
