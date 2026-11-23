package com.masterofchessstrategy.games.tiger_goat

import com.masterofchessstrategy.engine.games.tiger_goat.TigerGoatAction
import com.masterofchessstrategy.engine.games.tiger_goat.TigerGoatSearchBridge
import com.masterofchessstrategy.engine.games.tiger_goat.TigerGoatSearchLimits
import com.masterofchessstrategy.engine.games.tiger_goat.TigerGoatSearchRequest
import com.masterofchessstrategy.engine.games.tiger_goat.TigerGoatSearchResult
import com.masterofchessstrategy.engine.games.tiger_goat.TigerGoatSearchStop
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class TigerGoatAiPositionStamp(val sessionId: String, val revision: Long) {
    init { require(sessionId.isNotBlank() && revision >= 0) }
}

/** Complete serialized MOCT history, never a board-only projection. Native validates replay. */
class TigerGoatAiPositionSnapshot(val stamp: TigerGoatAiPositionStamp, bytes: ByteArray) {
    private val saved = bytes.copyOf()
    fun copyBytes(): ByteArray = saved.copyOf()
    internal fun samePosition(other: TigerGoatAiPositionSnapshot): Boolean =
        stamp == other.stamp && saved.contentEquals(other.saved)
}

sealed interface TigerGoatAiSearchState {
    data object Idle : TigerGoatAiSearchState
    data class Thinking(val stamp: TigerGoatAiPositionStamp) : TigerGoatAiSearchState
    data class Ready(val stamp: TigerGoatAiPositionStamp, val stop: TigerGoatSearchStop) : TigerGoatAiSearchState
    data class Failed(val stamp: TigerGoatAiPositionStamp, val cause: Throwable) : TigerGoatAiSearchState
    data object Closed : TigerGoatAiSearchState
}

data class TigerGoatAiSuggestion(
    val stamp: TigerGoatAiPositionStamp,
    val action: TigerGoatAction?,
    val stop: TigerGoatSearchStop,
    val score: Int?,
    val completedTurnDepth: Int,
    val visitedNodes: Long,
    val elapsedMillis: Long,
)

/** A new envelope preserves every original failure without mutating any caught throwable. */
class TigerGoatAiDrainException internal constructor(failures: List<Throwable>) :
    IllegalStateException("Tiger-goat AI worker failed (${failures.size} failures)", failures.first()) {
    init { failures.drop(1).forEach { addSuppressed(it) } }
}

/**
 * Owns one worker and immutable requests, but never owns/mutates the live rules session.
 * Control is thread-safe. The game owner must serialize setPosition/consume with its own
 * mutations, and apply a consumed action through Native before saving the checkpoint.
 * closeAndJoinBlocking seals admission first, waits through every accepted worker finally,
 * and reports all worker failures. Call it off the UI thread, before session/data teardown.
 */
class TigerGoatAiSearchCoordinator(private val bridge: TigerGoatSearchBridge? = null) {
    private val lock = Any()
    @Volatile private var workerThread: Thread? = null
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "tiger-goat-ai").also { workerThread = it }
    }
    private var sealed = false
    private var generation = Any()
    private var position: TigerGoatAiPositionSnapshot? = null
    private var active: Operation? = null
    private var ready: Operation? = null
    private var currentState: TigerGoatAiSearchState = TigerGoatAiSearchState.Idle
    private val workerFailures = mutableListOf<Throwable>()
    val state: TigerGoatAiSearchState get() = synchronized(lock) { currentState }
    val isSearchRunning: Boolean get() = synchronized(lock) { active != null }
    val failures: List<Throwable> get() = synchronized(lock) { workerFailures.toList() }

    private class Operation(
        val position: TigerGoatAiPositionSnapshot,
        val generation: Any,
        val request: TigerGoatSearchRequest,
    ) { var result: TigerGoatSearchResult? = null }

    /** Also invalidates on same-stamp/same-bytes replacement (restore/undo ABA). */
    fun setPosition(value: TigerGoatAiPositionSnapshot): Boolean = synchronized(lock) {
        if (sealed) return false
        invalidate()
        position = value
        true
    }

    /** False while a cancelled Native call is still unwinding; no unbounded request queue. */
    fun start(limits: TigerGoatSearchLimits = TigerGoatSearchLimits()): Boolean = synchronized(lock) {
        if (sealed || active != null) return false
        val source = position ?: return false
        val request = TigerGoatSearchRequest.prepare(source.copyBytes(), limits, bridge)
        invalidate()
        val operation = Operation(source, generation, request)
        active = operation
        currentState = TigerGoatAiSearchState.Thinking(source.stamp)
        try {
            worker.execute { runSearch(operation) }
        } catch (failure: Throwable) {
            active = null
            request.cancel()
            workerFailures.add(failure)
            currentState = TigerGoatAiSearchState.Failed(source.stamp, failure)
            throw failure
        }
        true
    }

    fun cancel() = synchronized(lock) {
        if (!sealed) invalidate()
    }

    /**
     * Once only. Rechecks full bytes and public identity under the private generation.
     * Call at the actual apply boundary on the game owner's serialized control thread.
     */
    fun consume(current: TigerGoatAiPositionSnapshot): TigerGoatAiSuggestion? = synchronized(lock) {
        val operation = ready ?: return null
        if (!isCurrent(operation) || !operation.position.samePosition(current)) {
            invalidate()
            return null
        }
        val result = operation.result ?: return null
        ready = null
        currentState = TigerGoatAiSearchState.Idle
        TigerGoatAiSuggestion(current.stamp, result.action, result.stop, result.score,
            result.completedTurnDepth, result.visitedNodes, result.elapsedMillis)
    }

    /** Non-blocking first half of close; safe for lifecycle invalidation on the UI thread. */
    fun seal() = synchronized(lock) {
        if (!sealed) {
            invalidate()
            sealed = true
            position = null
            currentState = TigerGoatAiSearchState.Closed
            worker.shutdown() // Never Future.cancel/shutdownNow: all accepted tasks run finally.
        }
    }

    fun closeAndJoinBlocking() {
        check(Thread.currentThread() !== workerThread) { "AI worker cannot join itself" }
        seal()
        var interrupted = false
        try {
            while (true) {
                try {
                    if (worker.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)) break
                } catch (_: InterruptedException) { interrupted = true }
            }
            val errors = failures
            if (errors.isNotEmpty()) throw TigerGoatAiDrainException(errors)
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun invalidate() {
        generation = Any()
        active?.request?.cancel()
        ready?.request?.cancel()
        ready = null
        currentState = TigerGoatAiSearchState.Idle
    }

    private fun isCurrent(operation: Operation): Boolean = !sealed &&
        generation === operation.generation && !operation.request.isCancellationRequested &&
        position?.samePosition(operation.position) == true

    private fun runSearch(operation: Operation) {
        try {
            val result = operation.request.executeBlocking()
            synchronized(lock) {
                if (isCurrent(operation)) {
                    operation.result = result
                    ready = operation
                    currentState = TigerGoatAiSearchState.Ready(operation.position.stamp, result.stop)
                }
            }
        } catch (failure: Throwable) {
            synchronized(lock) {
                workerFailures.add(failure) // Stale/cancelled errors remain observable at drain.
                if (isCurrent(operation)) {
                    currentState = TigerGoatAiSearchState.Failed(operation.position.stamp, failure)
                }
            }
        } finally {
            synchronized(lock) { if (active === operation) active = null }
        }
    }
}
