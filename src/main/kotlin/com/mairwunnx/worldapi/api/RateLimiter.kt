package com.mairwunnx.worldapi.api

import com.mairwunnx.worldapi.config.RateLimit
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.math.min

/** Token buckets per client key. Buckets that sit full for a while are dropped to bound memory. */
class RateLimiter(private val clock: () -> Long = System::nanoTime) {
    private class Bucket(var tokens: Double, var updated: Long)

    private val buckets = ConcurrentHashMap<String, Bucket>()

    /** Takes one request from the client's bucket; returns 0 when allowed, or seconds to wait. */
    fun acquire(key: String, limit: RateLimit): Long {
        val now = clock()
        val perNano = limit.requestsPerMinute / 60e9
        var wait = 0L
        buckets.compute(key) { _, existing ->
            val bucket = existing ?: Bucket(limit.burst.toDouble(), now)
            bucket.tokens = min(limit.burst.toDouble(), bucket.tokens + (now - bucket.updated) * perNano)
            bucket.updated = now
            if (bucket.tokens >= 1) bucket.tokens -= 1 else wait = ceil((1 - bucket.tokens) / perNano / 1e9).toLong()
            bucket
        }
        if (buckets.size > 10_000) evictIdle(now)
        return wait
    }

    private fun evictIdle(now: Long) {
        buckets.entries.removeIf { now - it.value.updated > 600_000_000_000L }
    }
}
