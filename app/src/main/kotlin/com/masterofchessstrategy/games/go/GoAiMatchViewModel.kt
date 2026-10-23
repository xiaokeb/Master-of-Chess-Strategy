package com.masterofchessstrategy.games.go

import androidx.annotation.MainThread
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.masterofchessstrategy.engine.*
import com.masterofchessstrategy.settings.LocalDataWriterRetirement
import com.masterofchessstrategy.settings.LocalDataWriterFailure
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

/** App-owned adapter for the eventual registered Native runtime. Close must cancel/join NN work. */
interface GoAiMatchEngineOwner : GoAiSearchEngine {
    suspend fun closeAndJoin()
}

data class GoAiMatchPageState(
    val loadingModel: Boolean = true,
    val error: String? = null,
    val match: GoAiMatchUiState? = null,
    val completedStorageReady: Boolean = false,
)

class GoAiMatchViewModel(
    private val repository: GoAiMatchRepository,
    private val config: GoAiMatchConfig,
    private val loadEngine: suspend () -> GoAiMatchEngineOwner,
    private val worker: CoroutineDispatcher = Dispatchers.Default,
    private val completedSink: GoAiCompletedMatchSink? = null,
    private val specifiedOpening: GoAiOpening? = null,
    private val completeHumanImmediately: Boolean = false,
    private val engineFactory: (Int, Int, Int) -> GoRecordEngine = { size, komi, handicap -> NativeGoEngine(size, komi, handicap) },
) : ViewModel(), LocalDataWriterRetirement {
    private val disposed = AtomicBoolean(false)
    private val mutableState = MutableStateFlow(GoAiMatchPageState())
    val state: StateFlow<GoAiMatchPageState> = mutableState.asStateFlow()
    private var opening: Job? = null
    private var engineOwner: GoAiMatchEngineOwner? = null
    private var session: GoAiMatchSession? = null
    private val retirement = CompletableDeferred<Unit>()
    override val retirementCompletion: Deferred<Unit> = retirement
    private var retirementStarted = false
    private var openingCleanupFailure: Throwable? = null

    init { openModel() }

    fun retryModel() {
        if (!disposed.get() && opening?.isActive != true && session == null) openModel()
    }

    private fun openModel() {
        mutableState.value = GoAiMatchPageState()
        opening = viewModelScope.launch {
            var candidate: GoAiMatchEngineOwner? = null
            try {
                // Native model construction is synchronous; retain ownership across cancellation.
                withContext(worker + NonCancellable) { candidate = loadEngine() }
                ensureActive()
                if (disposed.get()) return@launch
                val owned = checkNotNull(candidate)
                val next = GoAiMatchSession(viewModelScope, repository, config, owned, worker, specifiedOpening,
                    completedSink, completeHumanImmediately = completeHumanImmediately, engineFactory = engineFactory)
                engineOwner = owned; candidate = null
                session = next
                next.state.collect { match ->
                    if (!disposed.get()) mutableState.value = GoAiMatchPageState(loadingModel = false, match = match,
                        completedStorageReady = completedSink != null)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (!disposed.get()) mutableState.value = GoAiMatchPageState(
                    loadingModel = false, error = "围棋 AI 无法准备完成；存档已保留，请重试。")
            } finally {
                withContext(NonCancellable) {
                    var failure: Throwable? = null
                    withContext(worker) {
                        try { candidate?.closeAndJoin() } catch (error: Throwable) { failure = error }
                    }
                    failure?.let { error ->
                        // Keep failed ownership and report it through the stable retirement barrier.
                        openingCleanupFailure = error
                        engineOwner = candidate
                        beginRetirement()
                    }
                }
            }
        }
    }

    fun humanAction(action: GoAction) = !disposed.get() && (session?.humanAction(action) ?: false)
    fun undo() = !disposed.get() && (session?.undo() ?: false)
    fun requestHint() = !disposed.get() && (session?.requestHint() ?: false)
    fun restart() = !disposed.get() && (session?.restart(completedSink) ?: false)
    fun replaceOpening(opening: GoAiOpening) = !disposed.get() && (session?.replaceOpening(opening, completedSink) ?: false)
    fun setAutomatic(automatic: Boolean) = !disposed.get() && (session?.setAutomatic(automatic) ?: false)
    fun configureAutoContinuation(enabled: Boolean, maxGames: Int = 1) =
        !disposed.get() && (session?.configureAutoContinuation(enabled, maxGames) ?: false)
    fun retryAutoContinuation() = !disposed.get() && (session?.retryAutoContinuation() ?: false)
    fun retryCompletion() = !disposed.get() && (session?.retryCompletion() ?: false)
    fun pause() { if (!disposed.get()) session?.pause() }
    fun resume() { if (!disposed.get()) session?.resume() }
    fun retrySearch() { if (!disposed.get()) session?.retrySearch() }
    fun setSpeed(speed: Float) { if (!disposed.get()) session?.setSpeed(speed) }

    /** One independent cleanup survives viewModelScope cancellation and remains awaitable. */
    @MainThread
    override fun beginRetirement(): Deferred<Unit> {
        if (retirementStarted) return retirementCompletion
        retirementStarted = true
        disposed.set(true)
        val failures = mutableListOf<Throwable>()
        fun remember(error: Throwable) { failures.add(error) }
        try { session?.beginRetirement() } catch (error: Throwable) { remember(error) }
        try { opening?.cancel() } catch (error: Throwable) { remember(error) }
        mutableState.value = mutableState.value.copy(loadingModel = false, match = session?.state?.value)
        val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        cleanup.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                withContext(NonCancellable) {
                    try { opening?.join() } catch (error: Throwable) { remember(error) }
                    openingCleanupFailure?.let(::remember)
                    try { session?.closeAndJoin() } catch (error: Throwable) { remember(error) }
                    // Even a rules-close failure must not skip release of the model owner.
                    // A late candidate was already released (or failed release) by opening's
                    // finally. Retain its failure/ownership; never attempt unknown disposal twice.
                    try { if (openingCleanupFailure == null) withContext(worker) { engineOwner?.closeAndJoin() } }
                    catch (error: Throwable) { remember(error) }
                }
                val error = LocalDataWriterFailure.combine(failures)
                if (error == null) {
                    session = null; engineOwner = null
                    retirement.complete(Unit)
                } else retirement.completeExceptionally(error)
            } finally {
                cleanup.cancel()
            }
        }
        return retirementCompletion
    }

    override fun onCleared() {
        beginRetirement()
        super.onCleared()
    }
}
