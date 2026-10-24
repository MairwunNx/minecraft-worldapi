package com.mairwunnx.worldapi.modcore

import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

/**
 * WorldAPI's shared code logs through java.util.logging, as Paper hands it; the game logs through SLF4J, which java.util
 * logging doesn't reach on its own. This logger passes every line on, so it lands in the game's log with the rest.
 */
object Logs {
    val logger: Logger = Logger.getLogger("WorldAPI.mod").apply {
        useParentHandlers = false
        val target = org.slf4j.LoggerFactory.getLogger("WorldAPI")
        addHandler(object : Handler() {
            override fun publish(record: LogRecord) {
                val message = record.message?.let { if (record.parameters.isNullOrEmpty()) it else java.text.MessageFormat.format(it, *record.parameters) } ?: return
                when {
                    record.level.intValue() >= Level.SEVERE.intValue() -> target.error(message, record.thrown)
                    record.level.intValue() >= Level.WARNING.intValue() -> target.warn(message, record.thrown)
                    record.level.intValue() >= Level.INFO.intValue() -> target.info(message, record.thrown)
                    else -> target.debug(message, record.thrown)
                }
            }

            override fun flush() {}
            override fun close() {}
        })
    }
}
