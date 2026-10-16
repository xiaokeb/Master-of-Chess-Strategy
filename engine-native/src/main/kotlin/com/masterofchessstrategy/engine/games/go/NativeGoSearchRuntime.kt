package com.masterofchessstrategy.engine

import com.masterofchessstrategy.engine.internal.GoSearchBindings
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Real, locked KataGo CPU model. Construct/close on a worker; no fallback search. */
class NativeGoSearchRuntime(modelPath: String) : GoSearchRuntime {
    private val lock = ReentrantLock()
    private val idle = lock.newCondition()
    private var closing = false
    private var handle = run {
        require(modelPath.startsWith('/') && '\u0000' !in modelPath && modelPath.length <= 4096)
        GoSearchBindings.create(modelPath).also { check(it > 0) { "Go search runtime creation failed" } }
    }

    override fun prepare(snapshot: ByteArray, budget: GoSearchBudget): GoSearchRequest = lock.withLock {
        check(handle != 0L) { "Go search runtime is closed" }
        require(snapshot.size in 1..GoSearchRuntime.MAX_SNAPSHOT_BYTES)
        NativeRequest(GoSearchBindings.prepare(handle, snapshot.copyOf(), budget.maxVisits, budget.maxTimeMillis))
    }

    override fun cancelAll() = lock.withLock {
        if (handle != 0L) GoSearchBindings.cancelAll(handle)
    }

    /** Internal diagnostics only; rows are not moves, wins, elapsed time or strength. */
    internal fun processedRows(): Long = lock.withLock {
        check(handle != 0L) { "Go search runtime is closed" }
        GoSearchBindings.processedRows(handle)
    }

    override fun close() {
        val owned = lock.withLock {
            while (closing) idle.awaitUninterruptibly()
            if (handle == 0L) return
            closing = true
            handle.also { handle = 0L }
        }
        try { GoSearchBindings.closeRuntime(owned) }
        finally { lock.withLock { closing = false; idle.signalAll() } }
    }

    private class NativeRequest(private var handle: Long) : GoSearchRequest {
        private val lock = ReentrantLock()
        private val idle = lock.newCondition()
        private var closing = false
        private var running = false
        private var consumed = false
        init { check(handle > 0) { "Go search request creation failed" } }

        override fun run(): GoSearchResult {
            val owned = lock.withLock {
                check(handle != 0L && !closing && !consumed) { "Go request is closed or already consumed" }
                consumed = true
                running = true
                handle
            }
            try { return GoSearchResultDecoder.decode(GoSearchBindings.run(owned)) }
            finally { lock.withLock { running = false; idle.signalAll() } }
        }

        override fun cancel() = lock.withLock {
            if (handle != 0L) GoSearchBindings.cancel(handle)
        }

        override fun close() {
            val owned = lock.withLock {
                while (closing) idle.awaitUninterruptibly()
                if (handle == 0L) return
                closing = true
                GoSearchBindings.cancel(handle)
                while (running) idle.awaitUninterruptibly()
                handle.also { handle = 0L }
            }
            try { GoSearchBindings.closeRequest(owned) }
            finally { lock.withLock { closing = false; idle.signalAll() } }
        }
    }
}
