package com.masterofchessstrategy.games.go

import com.masterofchessstrategy.engine.GoAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

data class GoAiPositionStamp(val sessionId: String, val revision: Long) {
    init { require(sessionId.isNotBlank() && revision >= 0) }
}

class GoAiPositionSnapshot(val stamp: GoAiPositionStamp, bytes: ByteArray) {
    private val saved = bytes.copyOf()
    init { require(saved.isNotEmpty()) }
    fun copyBytes(): ByteArray = saved.copyOf()
}

class GoAiCancellation {
    private val cancelled = AtomicBoolean(false)
    private val listenerLock = Any()
    private var nativeCancel: (() -> Unit)? = null
    val isCancelled: Boolean get() = cancelled.get()
    fun cancel() = synchronized(listenerLock) {
        if (!cancelled.getAndSet(true)) nativeCancel?.invoke()
    }

    /** Adapter registers its immediate native request cancellation before entering search.
     * Callback must only mark cancellation, without blocking or throwing. Close registration
     * before releasing the native request; detach waits for a concurrent callback to return.
     */
    fun attachNativeCancellation(callback: () -> Unit): AutoCloseable = synchronized(listenerLock) {
        check(nativeCancel == null) { "Only one native request may attach to this cancellation token" }
        nativeCancel = callback
        if (isCancelled) callback()
        AutoCloseable {
            synchronized(listenerLock) {
                if (nativeCancel === callback) nativeCancel = null
            }
        }
    }
}

data class GoAiSearchRequest(
    val position: GoAiPositionSnapshot,
    val budget: GoAiSearchBudget,
    val cancellation: GoAiCancellation,
)

data class GoAiSuggestion(
    val action: GoAction,
    val visits: Int,
    val elapsedMillis: Double,
    val whiteWinProbability: Double,
    val whiteMinusBlackPoints: Double,
)

sealed interface GoAiSearchOutcome {
    data class Move(val suggestion: GoAiSuggestion) : GoAiSearchOutcome
    data object Cancelled : GoAiSearchOutcome
    data object Busy : GoAiSearchOutcome
    data object BudgetExhausted : GoAiSearchOutcome
}

/** Native adapter must propagate request cancellation during its synchronous search. */
fun interface GoAiSearchEngine {
    suspend fun search(request: GoAiSearchRequest): GoAiSearchOutcome
}

sealed interface GoAiSearchState {
    data object Idle : GoAiSearchState
    data class Thinking(val stamp: GoAiPositionStamp, val budget: GoAiSearchBudget) : GoAiSearchState
    data class Ready(val stamp: GoAiPositionStamp, val suggestion: GoAiSuggestion) : GoAiSearchState
    data class Failed(val stamp: GoAiPositionStamp, val message: String) : GoAiSearchState
}

/** All control calls/currentStamp run on the owning UI dispatcher; only search runs off-thread.
 * This object owns its child jobs, but borrows the engine. Call closeAndJoin before native close.
 */
class GoAiSearchCoordinator(
    parentScope: CoroutineScope,
    private val engine: GoAiSearchEngine,
    private val currentStamp: () -> GoAiPositionStamp,
    private val searchDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val lifetime = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + lifetime)
    private val mutableState = MutableStateFlow<GoAiSearchState>(GoAiSearchState.Idle)
    val state: StateFlow<GoAiSearchState> = mutableState.asStateFlow()
    private var active: Active? = null
    private var closed = false

    private class Active(val request: GoAiSearchRequest) { var job: Job? = null }

    fun start(position: GoAiPositionSnapshot, budget: GoAiSearchBudget): Boolean {
        if (closed || !lifetime.isActive || position.stamp != currentStamp()) return false
        cancel()
        val operation = Active(GoAiSearchRequest(position, budget, GoAiCancellation()))
        active = operation
        mutableState.value = GoAiSearchState.Thinking(position.stamp, budget)
        // Native can block the worker until its next NN boundary. A separate child signals
        // its atomic token promptly when the owning ViewModel scope is cancelled.
        val cancellationObserver = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() }
            finally {
                if (!lifetime.isActive) operation.request.cancellation.cancel()
            }
        }
        operation.job = scope.launch {
            try {
                val outcome = withContext(searchDispatcher) { engine.search(operation.request) }
                if (!canPublish(operation)) return@launch
                mutableState.value = when (outcome) {
                    is GoAiSearchOutcome.Move -> {
                        validate(outcome.suggestion, budget)
                        GoAiSearchState.Ready(position.stamp, outcome.suggestion)
                    }
                    GoAiSearchOutcome.Cancelled -> GoAiSearchState.Idle
                    GoAiSearchOutcome.Busy -> GoAiSearchState.Failed(position.stamp, "上一次围棋 AI 搜索正在退出，请稍后重试。")
                    GoAiSearchOutcome.BudgetExhausted -> GoAiSearchState.Failed(position.stamp, "思考预算已用完，未取得可用着法，请重试。")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (canPublish(operation)) mutableState.value = GoAiSearchState.Failed(
                    position.stamp, "围棋 AI 搜索失败，当前对局已保留。")
            } finally {
                cancellationObserver.cancel()
                if (active === operation) {
                    active = null
                    if (mutableState.value is GoAiSearchState.Thinking) mutableState.value = GoAiSearchState.Idle
                }
            }
        }
        return true
    }

    fun cancel() {
        active?.request?.cancellation?.cancel()
        active?.job?.cancel()
        active = null
        mutableState.value = GoAiSearchState.Idle
    }

    /** Owner keeps new start admission disabled while joining cancelled work. Runtime is reusable. */
    suspend fun cancelAndJoin() {
        cancel()
        withContext(kotlinx.coroutines.NonCancellable) {
            lifetime.children.toList().forEach { it.join() }
        }
    }

    fun positionChanged() {
        val current = currentStamp()
        val old = when (val value = mutableState.value) {
            is GoAiSearchState.Thinking -> value.stamp
            is GoAiSearchState.Ready -> value.stamp
            is GoAiSearchState.Failed -> value.stamp
            GoAiSearchState.Idle -> null
        }
        if (old != null && old != current) cancel()
    }

    /** Consumed once; caller still applies through Native and commits the durable checkpoint. */
    fun consume(stamp: GoAiPositionStamp): GoAiSuggestion? {
        val ready = mutableState.value as? GoAiSearchState.Ready ?: return null
        if (closed || !lifetime.isActive || ready.stamp != stamp || stamp != currentStamp()) {
            positionChanged()
            return null
        }
        mutableState.value = GoAiSearchState.Idle
        return ready.suggestion
    }

    suspend fun closeAndJoin() {
        closed = true
        cancel()
        lifetime.cancelAndJoin()
    }

    private fun canPublish(operation: Active): Boolean = !closed && lifetime.isActive && active === operation &&
        !operation.request.cancellation.isCancelled && operation.request.position.stamp == currentStamp()

    private fun validate(move: GoAiSuggestion, budget: GoAiSearchBudget) {
        require(move.action is GoAction.Place || move.action == GoAction.Pass)
        require(move.visits in 1..budget.maxVisits)
        require(move.elapsedMillis.isFinite() && move.elapsedMillis >= 0.0)
        require(move.whiteWinProbability.isFinite() && move.whiteWinProbability in 0.0..1.0)
        require(move.whiteMinusBlackPoints.isFinite())
    }
}
