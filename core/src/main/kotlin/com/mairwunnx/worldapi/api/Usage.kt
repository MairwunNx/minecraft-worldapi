package com.mairwunnx.worldapi.api

import java.util.concurrent.atomic.AtomicLong

/**
 * How much the API was used since the last read: REST requests endpoints served and WebSocket connections opened.
 * It outlives the HTTP server, which a reload may restart. Paper reports it to bStats, whose thread drains it.
 */
class Usage {
    private val requests = AtomicLong()
    private val sockets = AtomicLong()

    fun request() {
        requests.incrementAndGet()
    }

    fun socket() {
        sockets.incrementAndGet()
    }

    /** Requests since the last call, and starts the count over. */
    fun drainRequests(): Long = requests.getAndSet(0)

    /** Connections since the last call, and starts the count over. */
    fun drainSockets(): Long = sockets.getAndSet(0)
}
