package com.mairwunnx.worldapi

import com.mairwunnx.worldapi.metrics.Soft
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals

class MetricsTest {
    private fun logger(records: MutableList<LogRecord>) = Logger.getAnonymousLogger().apply {
        useParentHandlers = false
        addHandler(object : Handler() {
            override fun publish(record: LogRecord) { records += record }
            override fun flush() {}
            override fun close() {}
        })
    }

    @Test
    fun `a failing chart answers its fallback and warns once`() {
        val records = mutableListOf<LogRecord>()
        val soft = Soft(logger(records))
        val broken = soft.callable(0) { error("boom") }
        assertEquals(0, broken.call())
        assertEquals(0, broken.call())
        soft.warn(IllegalStateException("again"))
        assertEquals(1, records.size)
        assertEquals(7, soft.callable(0) { 7 }.call())
    }

    @Test
    fun `counts fit a line chart`() {
        assertEquals(0, Soft.clamp(-1))
        assertEquals(42, Soft.clamp(42))
        assertEquals(Int.MAX_VALUE, Soft.clamp(Long.MAX_VALUE))
    }
}
