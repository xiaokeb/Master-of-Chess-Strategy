package com.masterofchessstrategy.games.go

import com.masterofchessstrategy.engine.GoSearchBudget
import com.masterofchessstrategy.engine.GoSearchResult
import com.masterofchessstrategy.engine.GoSearchRuntime
import com.masterofchessstrategy.settings.LocalDataWriterFailure
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Owns the public runtime. Request detach/close precedes returning to the App coordinator. */
class GoAiRuntimeOwnerAdapter(
    private val runtime: GoSearchRuntime,
    private val worker: CoroutineDispatcher = Dispatchers.Default,
) : GoAiMatchEngineOwner {
    private class Lease(val cancellation: GoAiCancellation)
    private val lock = Any()
    private val active = mutableSetOf<Lease>()
    private var closing = false
    private val requestCleanupFailures = mutableListOf<Throwable>()
    private val drained = CompletableDeferred<Unit>()
    private val closed = CompletableDeferred<Unit>()

    override suspend fun search(request: GoAiSearchRequest): GoAiSearchOutcome = withContext(worker) {
        if (request.cancellation.isCancelled) return@withContext GoAiSearchOutcome.Cancelled
        val lease = synchronized(lock) {
            check(!closing) { "Go model owner is closed" }
            Lease(request.cancellation).also { active.add(it) }
        }
        try {
            val snapshot = request.position.copyBytes()
            require(snapshot.isNotEmpty() && snapshot.size <= GoSearchRuntime.MAX_SNAPSHOT_BYTES)
            val budget = GoSearchBudget(request.budget.maxVisits, request.budget.maxTimeMillis)
            val nativeRequest = runtime.prepare(snapshot, budget)
            var primaryFailure: Throwable? = null
            try {
                val cancellation = request.cancellation.attachNativeCancellation(nativeRequest::cancel)
                try {
                    if (request.cancellation.isCancelled) return@withContext GoAiSearchOutcome.Cancelled
                    val result = nativeRequest.run()
                    if (request.cancellation.isCancelled || synchronized(lock) { closing })
                        return@withContext GoAiSearchOutcome.Cancelled
                    when (result) {
                        is GoSearchResult.Move -> {
                            require(result.metrics.visits <= budget.maxVisits) { "Search exceeded visit budget" }
                            GoAiSearchOutcome.Move(GoAiSuggestion(result.action, result.metrics.visits,
                                result.metrics.elapsedMillis, result.metrics.whiteWinProbability, result.metrics.whiteMinusBlackPoints))
                        }
                        GoSearchResult.Cancelled -> GoAiSearchOutcome.Cancelled
                        GoSearchResult.Busy -> GoAiSearchOutcome.Busy
                        GoSearchResult.BudgetExhausted -> GoAiSearchOutcome.BudgetExhausted
                    }
                } finally { cancellation.close() }
            } catch (failure: Throwable) {
                primaryFailure = failure
                throw failure
            } finally {
                // Do not allocate an ownership wrapper after acquiring the native request.
                try { nativeRequest.close() } catch (failure: Throwable) {
                    rememberRequestCleanupFailure(failure)
                    val primary = primaryFailure
                    throw checkNotNull(LocalDataWriterFailure.combine(listOfNotNull(primary, failure)))
                }
            }
        } finally {
            val complete = synchronized(lock) { active.remove(lease); closing && active.isEmpty() }
            if (complete) drained.complete(Unit)
        }
    }

    private fun rememberRequestCleanupFailure(failure: Throwable) {
        synchronized(lock) {
            requestCleanupFailures.add(failure)
        }
    }

    /** Seal before dispatching, cancel every admitted request, then await its entire finally. */
    override suspend fun closeAndJoin() = withContext(NonCancellable) {
        val leases = synchronized(lock) {
            if (closing) null else {
                closing = true
                active.toList()
            }
        }
        if (leases != null) {
            val failures = mutableListOf<Throwable>()
            fun remember(error: Throwable) { failures.add(error) }
            leases.forEach { lease ->
                try { lease.cancellation.cancel() } catch (error: Throwable) { remember(error) }
            }
            if (leases.isEmpty()) drained.complete(Unit)
            withContext(worker) {
                drained.await() // Covers preparing, running, callback detach and request close.
                synchronized(lock) { requestCleanupFailures.toList() }.forEach(::remember)
                try { runtime.close() } catch (error: Throwable) { remember(error) }
            }
            val error = LocalDataWriterFailure.combine(failures)
            if (error == null) closed.complete(Unit) else closed.completeExceptionally(error)
        }
        closed.await() // Concurrent and later callers observe the same disposal failure too.
    }
}
