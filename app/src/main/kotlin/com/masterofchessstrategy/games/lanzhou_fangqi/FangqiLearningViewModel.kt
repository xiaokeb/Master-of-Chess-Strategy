package com.masterofchessstrategy.games.lanzhou_fangqi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.masterofchessstrategy.data.MocsDatabase
import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.games.lanzhou_fangqi.FangqiSession
import com.masterofchessstrategy.engine.games.lanzhou_fangqi.FangqiSide
import com.masterofchessstrategy.engine.games.lanzhou_fangqi.FangqiSnapshot
import com.masterofchessstrategy.engine.games.lanzhou_fangqi.NativeLanzhouFangqiSession
import com.masterofchessstrategy.games.lanzhou_fangqi.data.FangqiLearningSnapshot
import com.masterofchessstrategy.games.lanzhou_fangqi.data.FangqiLearningStore
import com.masterofchessstrategy.games.lanzhou_fangqi.data.FangqiLocalStorage
import com.masterofchessstrategy.games.lanzhou_fangqi.data.FangqiStorageEpochGate
import com.masterofchessstrategy.games.lanzhou_fangqi.data.FangqiStorageLease
import com.masterofchessstrategy.games.lanzhou_fangqi.data.RoomFangqiLearningStore
import com.masterofchessstrategy.settings.LocalDataWriterFailure
import com.masterofchessstrategy.settings.LocalDataWriterRetirement
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect

internal data class FangqiLearningPageState(
    val loading: Boolean = true,
    val progress: FangqiLearningSnapshot? = null,
    val pendingContentKey: String? = null,
    val saving: Boolean = false,
    val error: String? = null,
    val practiceId: String? = null,
    val openingPractice: Boolean = false,
    val session: FangqiSession? = null,
    val interaction: LanzhouFangqiInteraction? = null,
    val leaving: Boolean = false,
    val retired: Boolean = false,
    val closeFailed: Boolean = false,
)

/** Owns practice Native and progress writes across configuration changes; Root registers retirement. */
internal class FangqiLearningViewModel internal constructor(
    private val store: FangqiLearningStore,
    private val gate: FangqiStorageEpochGate,
    private val expectedEpoch: Long,
    private val sessionFactory: (List<Int>, FangqiSide) -> FangqiSession =
        NativeLanzhouFangqiSession::fromPosition,
    private val worker: CoroutineDispatcher = Dispatchers.Default,
    // Production shares the application's same-DB Native owner chain; tests can supply an explicit lease.
    private val acquireNativeLease: (suspend () -> FangqiStorageLease)? = null,
) : ViewModel(), LocalDataWriterRetirement {
    private val mutableState = MutableStateFlow(FangqiLearningPageState())
    val state: StateFlow<FangqiLearningPageState> = mutableState.asStateFlow()
    private var operation: Job? = null
    private var opening: Job? = null
    private var epochWatcher: Job? = null
    private var native: FangqiSession? = null
    private var nativeLease: FangqiStorageLease? = null
    private var controller: LanzhouFangqiInteraction? = null
    private var generation = 0L
    private var retired = false
    private var retirementStarted = false
    private var explicitlyLeft = false
    private var closeFailure: Throwable? = null
    private val cleanupFailures = mutableListOf<Throwable>()
    private val retirement = CompletableDeferred<Unit>()
    override val retirementCompletion: Deferred<Unit> get() = retirement
    private var exitAttempt: Deferred<Boolean>? = null
    private var exitIdentity: Triple<Int, Boolean, Long>? = null
    private var pending: Pending? = null

    private class Pending(val id: String, val practice: Boolean, bytes: ByteArray? = null) {
        val bytes = bytes?.copyOf()
        val key = (if (practice) "practice:" else "lesson:") + id
        var acknowledge: ((Boolean) -> Unit)? = null
    }

    init {
        require(expectedEpoch >= 0)
        load()
        epochWatcher = viewModelScope.launch {
            gate.epoch.collect { if (it != expectedEpoch) beginRetirement() }
        }
    }

    fun retryLoad() {
        if (!retired && !state.value.leaving && state.value.progress == null && operation?.isActive != true) load()
    }

    private fun load() {
        mutableState.value = state.value.copy(loading = true, error = null)
        operation = viewModelScope.launch {
            try {
                val loaded = store.load()
                if (!currentOwner()) return@launch
                mutableState.value = state.value.copy(loading = false, progress = loaded, error = null)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Throwable) {
                if (!retired) mutableState.value = state.value.copy(loading = false,
                    error = "学习进度读取失败，请重试。原记录已保留")
            }
        }
    }

    fun acknowledgeLesson(id: String) {
        require(LanzhouFangqiLearningContent.lessons.any { it.id == id })
        if (!canStartOperation() || state.value.practiceId != null || id in state.value.progress!!.completedLessons) return
        pending = Pending(id, practice = false)
        runPending()
    }

    fun retrySave() {
        if (!retired && pending != null && operation?.isActive != true) runPending()
    }

    private fun runPending() {
        val evidence = pending ?: return
        if (retired || operation?.isActive == true) return
        mutableState.value = state.value.copy(pendingContentKey = evidence.key, saving = true, error = null)
        operation = viewModelScope.launch {
            try {
                val saved = if (evidence.practice) store.completePractice(evidence.id, checkNotNull(evidence.bytes).copyOf())
                    else store.acknowledgeLesson(evidence.id)
                if (!currentOwner() || pending !== evidence) return@launch
                pending = null
                // A real Store return is the only completion marker; no optimistic completion or rewards.
                mutableState.value = state.value.copy(progress = saved, pendingContentKey = null, saving = false, error = null)
                evidence.acknowledge?.invoke(true)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Throwable) {
                if (!retired && pending === evidence) {
                    mutableState.value = state.value.copy(saving = false,
                        error = "学习进度保存失败，请重试。当前进度尚未确认")
                    evidence.acknowledge?.invoke(false)
                }
            }
        }
    }

    private fun canStartOperation() = !retired && !state.value.leaving && !state.value.loading &&
        state.value.progress != null && pending == null && operation?.isActive != true && opening?.isActive != true

    fun openPractice(id: String) {
        val practice = LanzhouFangqiLearningContent.practices.singleOrNull { it.id == id }
            ?: throw IllegalArgumentException("未知方棋练习")
        if (!canStartOperation() || state.value.practiceId != null || closeFailure != null) return
        mutableState.value = state.value.copy(practiceId = id, openingPractice = true, error = null)
        val nextGeneration = ++generation
        opening = viewModelScope.launch {
            var candidate: FangqiSession? = null
            try {
                acquireNativeLease?.let { acquire ->
                    val acquired = acquire()
                    nativeLease = acquired
                    if (acquired.epoch != expectedEpoch || !currentOwner()) {
                        throw IOException("本地数据已替换，请重新进入方棋学习")
                    }
                }
                // Own a late creation even if this coroutine is cancelled before dispatcher delivery.
                withContext(NonCancellable + worker) {
                    candidate = sessionFactory(practice.board.toList(), FangqiSide.RED)
                    candidate = SeededPracticeSession(checkNotNull(candidate))
                }
                currentCoroutineContext().ensureActive()
                if (!currentOwner() || state.value.leaving) throw CancellationException("practice owner left")
                val session = checkNotNull(candidate)
                val interaction = LanzhouFangqiInteraction(session, aiDispatcher = worker,
                    onPositionChanged = { snapshot, history, done ->
                        onPracticeCheckpoint(id, nextGeneration, snapshot, history, done)
                    })
                native = session
                controller = interaction
                candidate = null
                mutableState.value = state.value.copy(openingPractice = false, session = session,
                    interaction = interaction, error = null)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Throwable) {
                if (!retired) mutableState.value = state.value.copy(openingPractice = false,
                    error = "练习暂时无法打开，请返回课程后重试")
            } finally {
                candidate?.let { detached ->
                    withContext(NonCancellable) {
                        try { withContext(worker) { detached.close() } }
                        catch (error: Throwable) {
                            native = detached
                            closeFailure = error
                            cleanupFailures += error
                            mutableState.value = state.value.copy(closeFailed = true,
                                error = "练习未能安全结束，请重启应用后重试")
                        }
                    }
                }
                if (native == null || closeFailure != null) {
                    nativeLease?.release(closeFailure)
                    nativeLease = null
                }
            }
        }
    }

    private fun onPracticeCheckpoint(id: String, ownerGeneration: Long, snapshot: FangqiSnapshot,
        history: ByteArray, done: (Boolean) -> Unit) {
        if (retired || state.value.leaving || state.value.practiceId != id || generation != ownerGeneration) {
            done(false); return
        }
        if (snapshot.result != GameResult.FIRST_PLAYER_WIN || snapshot.pendingCaptures != 0) {
            // Practice movement is retained by this VM, not written into the ordinary active-game slot.
            done(true); return
        }
        val existing = pending
        val evidence = if (existing == null) Pending(id, practice = true, history).also { pending = it } else {
            if (!existing.practice || existing.id != id || !checkNotNull(existing.bytes).contentEquals(history)) {
                done(false)
                mutableState.value = state.value.copy(error = "待保存练习历史已变化，请保留当前局面")
                return
            }
            existing
        }
        evidence.acknowledge = done
        runPending()
    }

    /** A stable request survives cancellation of an individual Compose waiter. */
    suspend fun backToLessons(requestId: Int = 0): Boolean = finishExit(requestId, toLessons = true)
    suspend fun leave(requestId: Int = 0): Boolean = finishExit(requestId, toLessons = false)

    private suspend fun finishExit(requestId: Int, toLessons: Boolean): Boolean {
        val identity = Triple(requestId, toLessons, generation)
        exitAttempt?.let {
            if (exitIdentity == identity) return it.await()
            if (it.isActive) {
                it.await()
                return finishExit(requestId, toLessons)
            }
        }
        if (retired) return false
        if (explicitlyLeft) return true
        mutableState.value = state.value.copy(leaving = true)
        controller?.seal()
        val attempt = viewModelScope.async {
            try {
                opening?.join()
                operation?.join()
                if (pending != null) {
                    mutableState.value = state.value.copy(error = "学习进度尚未保存，请重试保存后返回")
                    return@async false
                }
                closeNative()
                if (toLessons) {
                    mutableState.value = state.value.copy(practiceId = null, openingPractice = false,
                        session = null, interaction = null, leaving = false, error = null)
                } else {
                    explicitlyLeft = true
                    retirement.complete(Unit)
                }
                true
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Throwable) {
                mutableState.value = state.value.copy(error = "练习未能安全结束，请重启应用后重试", closeFailed = closeFailure != null)
                false
            }
        }
        exitIdentity = identity
        exitAttempt = attempt
        return attempt.await()
    }

    private suspend fun closeNative() = withContext(NonCancellable) {
        closeFailure?.let {
            nativeLease?.release(it)
            nativeLease = null
            throw it
        }
        val active = native
        try { if (active != null) withContext(worker) { active.close() } }
        catch (error: Throwable) {
            closeFailure = error
            nativeLease?.release(error)
            nativeLease = null
            throw error
        }
        native = null
        controller = null
        nativeLease?.release()
        nativeLease = null
    }

    private fun currentOwner(): Boolean {
        if (retired) return false
        if (gate.epoch.value != expectedEpoch) { beginRetirement(); return false }
        return true
    }

    override fun beginRetirement(): Deferred<Unit> {
        if (retirementStarted || explicitlyLeft) return retirementCompletion
        retirementStarted = true
        retired = true
        controller?.seal()
        mutableState.value = state.value.copy(loading = false, saving = false, leaving = true, retired = true)
        val jobs = listOfNotNull(operation, opening, epochWatcher, exitAttempt)
        jobs.forEach { it.cancel() }
        val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        cleanup.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                withContext(NonCancellable) {
                    jobs.joinAll()
                    try { closeNative() } catch (error: Throwable) { cleanupFailures += error }
                }
                val failure = LocalDataWriterFailure.combine(cleanupFailures)
                if (failure == null) retirement.complete(Unit) else {
                    mutableState.value = state.value.copy(closeFailed = true,
                        error = "练习未能安全结束，请重启应用后重试")
                    retirement.completeExceptionally(failure)
                }
            } finally { cleanup.cancel() }
        }
        return retirementCompletion
    }

    override fun onCleared() { beginRetirement(); super.onCleared() }

    /** This in-memory reset never changes the saved ordinary local match. */
    private class SeededPracticeSession(private val delegate: FangqiSession) : FangqiSession by delegate {
        private val seed = delegate.serialize().copyOf()
        override fun reset() {
            check(delegate.restore(seed.copyOf())) { "无法恢复方棋练习原始种子" }
        }
    }

    companion object {
        fun forDatabase(database: MocsDatabase,
            storage: FangqiLocalStorage = FangqiLocalStorage.forDatabase(database),
        ): FangqiLearningViewModel {
            val gate = FangqiStorageEpochGate.forDatabase(database)
            require(storage.gate === gate) { "方棋学习必须使用同数据库的Native所有者" }
            val owner = gate.captureOwner()
            return FangqiLearningViewModel(
                RoomFangqiLearningStore(database, database.fangqiLearningProgressDao(), gate, owner), gate, owner.epoch,
                acquireNativeLease = storage::acquire,
            )
        }
    }
}
