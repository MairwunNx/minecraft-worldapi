package com.mairwunnx.worldapi.core

/** Runs a block on the server thread and suspends the caller until it finishes. */
interface MainThread {
    suspend fun <T> call(block: () -> T): T

    /** Runs a block on the server thread: right away when already on it, else on the next tick, without waiting. */
    fun execute(block: () -> Unit)

    companion object {
        /** For tests and code already on the right thread. */
        val Immediate = object : MainThread {
            override suspend fun <T> call(block: () -> T): T = block()
            override fun execute(block: () -> Unit) = block()
        }
    }
}
