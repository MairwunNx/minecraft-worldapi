package com.mairwunnx.worldapi

import com.mairwunnx.worldapi.api.Usage
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals

class UsageTest {
    @Test
    fun `a read drains the count`() {
        val usage = Usage()
        repeat(3) { usage.request() }
        usage.socket()
        assertEquals(3, usage.drainRequests())
        assertEquals(0, usage.drainRequests())
        assertEquals(1, usage.drainSockets())
        assertEquals(0, usage.drainSockets())
    }

    @Test
    fun `counts from many threads add up`() {
        val usage = Usage()
        var drained = 0L
        val reader = thread { repeat(100) { drained += usage.drainRequests() } }
        (1..8).map { thread { repeat(10_000) { usage.request() } } }.forEach(Thread::join)
        reader.join()
        assertEquals(80_000, drained + usage.drainRequests())
    }
}
