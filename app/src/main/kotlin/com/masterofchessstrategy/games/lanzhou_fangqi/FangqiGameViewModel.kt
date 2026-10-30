package com.masterofchessstrategy.games.lanzhou_fangqi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.games.lanzhou_fangqi.FangqiSession
import com.masterofchessstrategy.engine.games.lanzhou_fangqi.FangqiSide
import com.masterofchessstrategy.engine.games.lanzhou_fangqi.FangqiSnapshot
import com.masterofchessstrategy.engine.games.lanzhou_fangqi.NativeLanzhouFangqiSession
import com.masterofchessstrategy.games.lanzhou_fangqi.data.FangqiLocalStorage
import com.masterofchessstrategy.games.lanzhou_fangqi.data.FangqiSessionAdmission
import com.masterofchessstrategy.games.lanzhou_fangqi.data.FangqiStorageLease
import com.masterofchessstrategy.settings.LocalDataWriterFailure
import com.masterofchessstrategy.settings.LocalDataWriterRetirement
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect

internal data class FangqiGamePageState(
    val loading: Boolean = true,
    val session: FangqiSession? = null,
    val interaction: LanzhouFangqiInteraction? = null,
    val mode: FangqiStoredMode? = null,
    val difficulty: Difficulty? = null,
    val error: String? = null,
    val retryLoad: Boolean = false,
    val retrySave: Boolean = false,
    val retryAi: Boolean = false,
    val leaving: Boolean = false,
    val retired: Boolean = false,
    val undoAllowed: Boolean = true,
    val historyLimited: Boolean = false,
    val aiBlocked: Boolean = false,
    val restartAllowed: Boolean = true,
)

/** Native and AI survive configuration changes; only real committed snapshots unlock play. */
internal class FangqiGameViewModel(
    private val storage: FangqiLocalStorage,
    private val requestedDifficulty: Difficulty? = null,
    private val sessionFactory: () -> FangqiSession = { NativeLanzhouFangqiSession() },
    private val worker: CoroutineDispatcher = Dispatchers.Default,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val expectedSessionId: String? = null,
    private val formal: Boolean = false,
) : ViewModel(), LocalDataWriterRetirement {
    init { require(!formal || expectedSessionId != null) }
    private val mutableState = MutableStateFlow(FangqiGamePageState())
    val state: StateFlow<FangqiGamePageState> = mutableState.asStateFlow()
    private var lease: FangqiStorageLease? = null
    private var session: FangqiSession? = null
    private var interaction: LanzhouFangqiInteraction? = null
    private var metadata: FangqiStoredSession? = null
    private var sessionId = ""
    private var undoUses = 0
    private var hintUses = 0
    private var opening: Job? = null
    private var observer: Job? = null
    private var ai: Job? = null
    private var checkpoint: Job? = null
    private var epochWatcher: Job? = null
    private var leaveAttempt: Deferred<Boolean>? = null
    private var leaveRequestId: Int? = null
    private var leaving = false
    private var retired = false
    private var explicitlyLeft = false
    private var retirementStarted = false
    private var nativeCloseFailure: Throwable? = null
    private var failedCandidate: FangqiSession? = null
    private val cleanupFailures = mutableListOf<Throwable>()
    private val retirement = CompletableDeferred<Unit>()
    override val retirementCompletion: Deferred<Unit> = retirement

    private class Pending(val saved: FangqiStoredSession, val revision: Long, var done: ((Boolean) -> Unit)?)
    private var pending: Pending? = null

    init { open() }

    fun retryLoad() {
        if (!retired && !leaving && state.value.retryLoad && opening?.isActive != true) open()
    }

    fun retrySave() {
        if (retired || leaving || checkpoint?.isActive == true) return
        if (interaction != null) interaction?.retrySave() else submitPendingRetry()
    }

    fun retryAi() {
        if (retired || leaving || !state.value.retryAi) return
        mutableState.value = state.value.copy(error = null, retryAi = false)
        startAiIfNeeded()
    }

    private fun open() {
        mutableState.value = FangqiGamePageState()
        opening = viewModelScope.launch(start = CoroutineStart.LAZY) {
            var candidate: FangqiSession? = null
            try {
                val currentLease = lease ?: (if (formal) storage.acquireRanked(checkNotNull(expectedSessionId))
                    else storage.acquire()).also { acquired ->
                    lease = acquired
                    epochWatcher = viewModelScope.launch {
                        storage.epoch.collect { if (it != acquired.epoch) beginRetirement() }
                    }
                }
                val saved = currentLease.autosave.loadAfterPendingWrites()
                if (retired || leaving) return@launch
                if (expectedSessionId != null && saved?.sessionId != expectedSessionId) {
                    throw IOException("指定方棋对局已改变，请返回后重新选择")
                }
                if (saved == null && (formal || requestedDifficulty != null)) {
                    throw IOException("请从人机入口确认难度并创建正式对局")
                }
                withContext(worker) {
                    candidate = sessionFactory()
                    if (saved != null) {
                        val engine = checkNotNull(candidate)
                        if (!engine.restore(saved.engineState) || !engine.serialize().contentEquals(saved.engineState)) {
                            throw IOException("方棋存档无法完整恢复，原数据已保留")
                        }
                    }
                }
                if (retired || leaving) return@launch
                val engine = checkNotNull(candidate)
                session = engine
                candidate = null
                metadata = saved
                sessionId = saved?.sessionId ?: newId()
                undoUses = saved?.undoUseCount ?: 0
                hintUses = saved?.hintUseCount ?: 0
                if (saved == null) {
                    val bytes = withContext(worker) { engine.serialize() }
                    val initial = FangqiStoredSession(
                        if (requestedDifficulty == null) FangqiStoredMode.LOCAL_TWO_PLAYER else FangqiStoredMode.HUMAN_VS_AI,
                        requestedDifficulty, bytes, sessionId, now(),
                    )
                    metadata = initial
                    val request = Pending(initial, currentLease.autosave.enqueue(initial), null)
                    pending = request
                    awaitCheckpoint(request)
                } else showReady()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (session != null && pending == null) {
                    try { closeNative() } catch (closeFailure: Throwable) {
                        cleanupFailures += closeFailure
                        beginRetirement()
                    }
                }
                if (!retired && !leaving) mutableState.value = state.value.copy(
                    loading = false, error = error.message ?: "方棋无法载入，原存档已保留", retryLoad = true)
            } catch (_: LinkageError) {
                if (!retired && !leaving) mutableState.value = state.value.copy(
                    loading = false, error = "方棋规则引擎暂不可用，请重试", retryLoad = true)
            } finally {
                val unowned = candidate
                if (unowned != null) withContext(NonCancellable) {
                    try { withContext(worker) { unowned.close() } } catch (error: Throwable) {
                        failedCandidate = unowned
                        cleanupFailures += error
                        beginRetirement()
                    }
                }
            }
        }
        opening!!.start()
    }

    private fun showReady() {
        if (retired || leaving) return
        val saved = checkNotNull(metadata)
        val engine = checkNotNull(session)
        val controller = LanzhouFangqiInteraction(
            engine,
            humanSide = if (saved.mode == FangqiStoredMode.HUMAN_VS_AI) FangqiSide.RED else null,
            aiDispatcher = worker,
            onPositionChanged = ::onCheckpoint,
            automatic = saved.mode == FangqiStoredMode.AI_AUTO_PLAY,
            undoAllowed = ::canUndo,
            onUndoAccepted = {
                if (undoUses < Int.MAX_VALUE) undoUses++
                mutableState.value = state.value.copy(undoAllowed = canUndo(), historyLimited = false, aiBlocked = false, error = null)
            },
            onRestartAccepted = {
                sessionId = newId()
                undoUses = 0
                hintUses = 0
                mutableState.value = state.value.copy(undoAllowed = canUndo(), historyLimited = false, aiBlocked = false, error = null)
            },
            restartAllowed = { !formal },
        )
        interaction = controller
        mutableState.value = FangqiGamePageState(loading = false, session = engine, interaction = controller,
            mode = saved.mode, difficulty = saved.difficulty, undoAllowed = canUndo(), restartAllowed = !formal)
        observer = viewModelScope.launch { controller.state.collect { startAiIfNeeded() } }
    }

    private fun onCheckpoint(@Suppress("UNUSED_PARAMETER") snapshot: FangqiSnapshot, bytes: ByteArray, done: (Boolean) -> Unit) {
        if (retired || leaving) { done(false); return }
        try {
            val currentLease = checkNotNull(lease)
            val existing = pending
            if (existing != null) {
                check(existing.saved.engineState.contentEquals(bytes)) { "待保存快照已变化，请重新载入" }
                existing.done = done
                check(currentLease.autosave.retryLatest() == existing.revision)
                launchCheckpoint(existing)
            } else {
                val previous = checkNotNull(metadata)
                // This is remaining history length, not lifetime actions or ranked participation.
                val count = FangqiSessionAdmission.inspectHistory(bytes)
                val saved = FangqiStoredSession(previous.mode, previous.difficulty, bytes, sessionId,
                    maxOf(now(), previous.updatedAtEpochMillis),
                    count, undoUses, hintUses)
                val next = Pending(saved, currentLease.autosave.enqueue(saved), done)
                pending = next
                launchCheckpoint(next)
            }
        } catch (error: Exception) {
            mutableState.value = state.value.copy(error = error.message ?: "方棋保存失败，请重试", retrySave = true)
            done(false)
        }
    }

    private fun canUndo(): Boolean {
        if (retired || leaving || formal && session?.snapshot()?.result != GameResult.ONGOING) return false
        val saved = metadata ?: return false
        if (saved.mode == FangqiStoredMode.LOCAL_TWO_PLAYER) return true
        return when (saved.difficulty) {
            Difficulty.EASY -> true
            Difficulty.MEDIUM -> undoUses < 3
            Difficulty.HARD -> undoUses < 1
            Difficulty.MASTER, null -> false
        }
    }

    private fun submitPendingRetry() {
        val request = pending ?: return
        try {
            check(checkNotNull(lease).autosave.retryLatest() == request.revision)
            launchCheckpoint(request)
        } catch (error: Exception) {
            mutableState.value = state.value.copy(error = error.message ?: "保存失败，请重试", retrySave = true)
        }
    }

    private fun launchCheckpoint(request: Pending) {
        if (checkpoint?.isActive == true) return
        mutableState.value = state.value.copy(error = null, retrySave = false)
        checkpoint = viewModelScope.launch(start = CoroutineStart.LAZY) { awaitCheckpoint(request) }
        checkpoint!!.start()
    }

    private suspend fun awaitCheckpoint(request: Pending): Boolean {
        val saved = checkNotNull(lease).autosave.awaitSaved(request.revision)
        if (pending !== request || retired) return false
        // A synchronous acknowledgement may schedule another capture checkpoint immediately.
        if (checkpoint === currentCoroutineContext()[Job]) checkpoint = null
        if (saved) {
            metadata = request.saved
            pending = null
            request.done?.invoke(true)
            if (!leaving) {
                mutableState.value = state.value.copy(loading = false, error = null, retrySave = false,
                    historyLimited = false, aiBlocked = false, undoAllowed = canUndo())
                if (interaction == null) showReady()
            }
        } else {
            request.done?.invoke(false)
            if (!retired) mutableState.value = state.value.copy(loading = false,
                error = "方棋保存失败，原快照已保留，请重试", retrySave = true)
        }
        return saved
    }

    private fun startAiIfNeeded() {
        if (retired || leaving || ai?.isActive == true || state.value.retryAi || state.value.retrySave ||
            state.value.aiBlocked) return
        val saved = metadata ?: return
        val controller = interaction ?: return
        val current = controller.state.value
        val automaticSide = saved.mode == FangqiStoredMode.AI_AUTO_PLAY ||
            saved.mode == FangqiStoredMode.HUMAN_VS_AI && current.snapshot.currentPlayer == FangqiSide.BLACK
        if (!automaticSide || current.savePending || current.saveFailed || current.aiThinking ||
            current.snapshot.result != GameResult.ONGOING) return
        val difficulty = saved.difficulty ?: return
        val turn = viewModelScope.launch(start = CoroutineStart.LAZY) {
            val side = controller.state.value.snapshot.currentPlayer
            controller.playAiTurn(difficulty, side)
            if (!retired && !leaving) {
                val after = controller.state.value
                if (after.feedback == FangqiFeedback.HISTORY_LIMIT) {
                    // A later selection may replace the interaction feedback; keep the owner fence until progress/restart.
                    mutableState.value = state.value.copy(error = "对局记录已达上限，可返回备份当前局或重开",
                        retryAi = false, historyLimited = true, aiBlocked = true)
                } else if (after.snapshot.result == GameResult.ONGOING && after.snapshot.currentPlayer == side &&
                    !after.saveFailed && !after.savePending) {
                    mutableState.value = state.value.copy(error = "AI暂不能继续走子，当前对局已保留，可返回备份或重开",
                        retryAi = false, aiBlocked = true)
                }
            }
        }
        ai = turn
        turn.invokeOnCompletion {
            if (!retired && !leaving) viewModelScope.launch { startAiIfNeeded() }
        }
        turn.start()
    }

    /** Owned by the VM, so cancelling a route waiter cannot orphan Native cleanup. */
    suspend fun leave(requestId: Int = 0): Boolean {
        if (explicitlyLeft) return true
        if (retired) return false
        if (leaveAttempt != null && (leaveAttempt!!.isActive || leaveRequestId == requestId)) return leaveAttempt!!.await()
        leaveRequestId = requestId
        leaving = true
        interaction?.seal()
        mutableState.value = state.value.copy(leaving = true, error = null)
        val attempt = viewModelScope.async {
            try {
                opening?.cancelAndJoin()
                observer?.cancelAndJoin()
                ai?.cancelAndJoin()
                checkpoint?.join()
                val request = pending
                if (request != null) {
                    val queue = checkNotNull(lease).autosave
                    if (!queue.awaitSaved(request.revision)) {
                        check(queue.retryLatest() == request.revision)
                        if (!awaitCheckpoint(request)) return@async leaveFailed()
                    } else if (!awaitCheckpoint(request)) return@async leaveFailed()
                }
                // Every submitted revision above is acknowledged. Do not reload a corrupt retained row to leave it safely.
                closeNative()
                LocalDataWriterFailure.combine(cleanupFailures)?.let { throw it }
                lease?.release()
                explicitlyLeft = true
                retirement.complete(Unit)
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (!retired) mutableState.value = state.value.copy(leaving = true,
                    error = error.message ?: "对局尚未保存完成，请重试返回", retrySave = true)
                false
            }
        }
        leaveAttempt = attempt
        return attempt.await()
    }

    private fun leaveFailed(): Boolean {
        mutableState.value = state.value.copy(leaving = true, error = "保存失败，请重试返回；未保存快照仍保留", retrySave = true)
        return false
    }

    private suspend fun closeNative() = withContext(NonCancellable) {
        nativeCloseFailure?.let { throw it }
        val engine = session ?: return@withContext
        try { withContext(worker) { engine.close() } } catch (error: Throwable) {
            // A failed release has unknown ownership; keep its handle and never retry disposal blindly.
            nativeCloseFailure = error
            throw error
        }
        session = null
        interaction = null
    }

    override fun beginRetirement(): Deferred<Unit> {
        if (retirementStarted || explicitlyLeft) return retirementCompletion
        retirementStarted = true
        retired = true
        leaving = true
        interaction?.seal()
        mutableState.value = state.value.copy(loading = false, leaving = true, retired = true)
        val jobs = listOfNotNull(opening, observer, ai, checkpoint, epochWatcher, leaveAttempt)
        jobs.forEach { it.cancel() }
        val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        cleanup.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                withContext(NonCancellable) {
                    jobs.joinAll()
                    try { lease?.retireWriter() } catch (error: Throwable) { cleanupFailures += error }
                    try { closeNative() } catch (error: Throwable) { cleanupFailures += error }
                }
                val failure = LocalDataWriterFailure.combine(cleanupFailures)
                lease?.release(failure)
                if (failure == null) retirement.complete(Unit) else retirement.completeExceptionally(failure)
            } finally { cleanup.cancel() }
        }
        return retirementCompletion
    }

    override fun onCleared() { beginRetirement(); super.onCleared() }
}
