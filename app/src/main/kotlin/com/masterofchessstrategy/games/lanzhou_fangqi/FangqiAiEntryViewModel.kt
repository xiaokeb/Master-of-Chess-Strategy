package com.masterofchessstrategy.games.lanzhou_fangqi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.games.lanzhou_fangqi.data.toRankedPosition
import com.masterofchessstrategy.settings.LocalDataWriterRetirement
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

internal data class FangqiAiEntryState(
    val loading: Boolean = true,
    val facts: FangqiAiEntryFacts? = null,
    val difficultyCode: Int? = null,
    val creating: Boolean = false,
    val opening: Boolean = false,
    val pendingCreation: Boolean = false,
    val launch: FangqiAiEntryLaunch? = null,
    val error: String? = null,
    val retired: Boolean = false,
)

/** A retained request owns its ID/time and outlives cancelled Compose waiters. */
internal class FangqiAiEntryViewModel(
    private val repository: FangqiAiEntryRepository,
    worker: CoroutineDispatcher = Dispatchers.IO,
    private val newSessionId: () -> String = { UUID.randomUUID().toString() },
    private val now: () -> Long = System::currentTimeMillis,
    initialDifficultyCode: Int? = null,
) : ViewModel(), LocalDataWriterRetirement {
    private class Request(val creation: FangqiRankedCreation, val previous: FangqiStoredSession?)
    private val lock = Any()
    private val operations = CoroutineScope(SupervisorJob() + worker)
    private val ownerEpoch = repository.ownerEpoch
    private val mutableState = MutableStateFlow(FangqiAiEntryState(difficultyCode = initialDifficultyCode))
    val state = mutableState.asStateFlow()
    private val retirement = CompletableDeferred<Unit>()
    override val retirementCompletion: Deferred<Unit> get() = retirement
    private var pending: Request? = null
    private var inFlight = false
    private var refreshPending = false
    private var hasResumed = false
    private var epochWatcher: Job? = null
    @Volatile internal var lastFailure: Throwable? = null
        private set

    init {
        reload()
        epochWatcher = viewModelScope.launch {
            repository.epoch.first { it != ownerEpoch }
            beginRetirement()
        }
    }

    fun resume() {
        val refresh = synchronized(lock) { val had = hasResumed; hasResumed = true; had }
        if (refresh) reload()
    }

    fun reload() {
        synchronized(lock) {
            if (!currentOwner()) return
            if (inFlight) { refreshPending = true; return }
            inFlight = true
            mutableState.value = state.value.copy(loading = true, opening = false, facts = null, error = null)
        }
        operations.launch {
            try {
                val facts = withContext(NonCancellable) { repository.loadFacts() }
                synchronized(lock) {
                    if (currentOwner() && !refreshPending) {
                        lastFailure = null
                        mutableState.value = state.value.copy(loading = false, facts = facts, pendingCreation = pending != null)
                    }
                }
            } catch (failure: Exception) { failed(failure, false) }
            catch (failure: LinkageError) { failed(failure, false) }
            finally { finished() }
        }
    }

    fun selectDifficulty(code: Int) = synchronized(lock) {
        if (!currentOwner() || inFlight || pending != null || state.value.launch != null || state.value.opening) return@synchronized
        mutableState.value = state.value.copy(difficultyCode = code, error = null)
    }

    fun createNew() {
        val request = synchronized(lock) {
            if (!currentOwner() || inFlight || pending != null || state.value.launch != null || state.value.opening) return
            val facts = state.value.facts ?: return
            val selection = facts.eligibility.select(state.value.difficultyCode)
            if (selection !is FangqiAiSelection.Eligible) {
                mutableState.value = state.value.copy(error = "请明确选择已满足条件的难度")
                return
            }
            if (facts.active != null && !facts.settledActive) {
                mutableState.value = state.value.copy(error = "当前对局尚未结束，请先继续当前对局")
                return
            }
            val creation = FangqiRankedCreation(1, FangqiRankedStartOrigin.NEW_STANDARD_HUMAN_GAME,
                FangqiRankedPosition(FangqiRankedIdentity(newSessionId(), GameType.LANZHOU_SQUARE_CHESS,
                    FangqiStoredMode.HUMAN_VS_AI, selection.difficulty, 0, FangqiRankedLedger.RULES_VERSION, 1),
                    FangqiRankedLedger.standardInitialHistory(), now(), 0, 0, 0))
            Request(creation, facts.active).also { pending = it; startCreate() }
        }
        submit(request)
    }

    fun retryCreate() {
        val request = synchronized(lock) {
            if (!currentOwner() || inFlight || state.value.launch != null || state.value.opening) return
            (pending ?: return).also { startCreate() }
        }
        submit(request)
    }

    private fun startCreate() {
        inFlight = true
        mutableState.value = state.value.copy(creating = true, pendingCreation = true, error = null)
    }

    private fun submit(request: Request) {
        operations.launch {
            try {
                val saved = withContext(NonCancellable) { repository.create(request.creation, request.previous) }
                if (saved.toRankedPosition() != request.creation.initial) throw IOException("正式新局确认与原请求不一致")
                synchronized(lock) {
                    if (currentOwner()) {
                        lastFailure = null
                        pending = null
                        mutableState.value = state.value.copy(loading = false, creating = false, pendingCreation = false,
                            launch = FangqiAiEntryLaunch(saved.sessionId, true), error = null)
                    }
                }
            } catch (failure: Exception) { failed(failure, true) }
            catch (failure: LinkageError) { failed(failure, true) }
            finally { finished() }
        }
    }

    fun resumeActive() = synchronized(lock) {
        if (!currentOwner() || inFlight || pending != null || state.value.launch != null || state.value.opening) return@synchronized
        val facts = state.value.facts ?: return@synchronized
        val active = facts.active ?: return@synchronized
        mutableState.value = state.value.copy(launch = FangqiAiEntryLaunch(active.sessionId, facts.formalActive), error = null)
    }

    /** Navigation consumes an acknowledged launch once; recreation cannot deliver it twice. */
    fun consumeLaunch(): FangqiAiEntryLaunch? = synchronized(lock) {
        if (!currentOwner()) return@synchronized null
        state.value.launch.also { if (it != null) mutableState.value = state.value.copy(launch = null, opening = true) }
    }

    private fun failed(failure: Throwable, creation: Boolean) = synchronized(lock) {
        lastFailure = failure
        if (currentOwner() && !refreshPending) mutableState.value = state.value.copy(loading = false, creating = false,
            pendingCreation = pending != null, error = if (creation) "新局尚未确认，请重试原请求；原对局已保留"
            else "菜单进度读取失败，请重试；原记录已保留")
    }

    private fun finished() {
        val refresh = synchronized(lock) {
            inFlight = false
            // A queued refresh may suppress a failed creation's intermediate error publication.
            // The operation still ended: never leave retry disabled behind a stale busy flag.
            mutableState.value = state.value.copy(creating = false)
            if (state.value.retired) { finishRetirement(); false }
            else refreshPending.also { refreshPending = false }
        }
        if (refresh) reload()
    }

    private fun currentOwner(): Boolean {
        if (repository.epoch.value != ownerEpoch) retireLocked()
        return !state.value.retired
    }

    private fun retireLocked() {
        if (state.value.retired) return
        refreshPending = false
        epochWatcher?.cancel()
        mutableState.value = state.value.copy(loading = false, creating = false, retired = true,
            launch = null, error = "本地数据已替换或页面已关闭，请返回后重新进入")
        if (!inFlight) finishRetirement()
    }

    override fun beginRetirement(): Deferred<Unit> = synchronized(lock) {
        retireLocked()
        retirementCompletion
    }

    private fun finishRetirement() { operations.cancel(); retirement.complete(Unit) }
    override fun onCleared() { beginRetirement(); super.onCleared() }
}
