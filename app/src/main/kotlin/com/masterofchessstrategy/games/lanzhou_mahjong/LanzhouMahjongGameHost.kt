package com.masterofchessstrategy.games.lanzhou_mahjong

import com.masterofchessstrategy.engine.*
import com.masterofchessstrategy.games.lanzhou_mahjong.data.LanzhouMahjongAdmittedSessionStore
import com.masterofchessstrategy.games.lanzhou_mahjong.data.LanzhouMahjongStorageEpochGate
import com.masterofchessstrategy.games.lanzhou_mahjong.data.LanzhouMahjongStoredSession
import com.masterofchessstrategy.settings.LocalDataWriterRetirement
import com.masterofchessstrategy.settings.LocalDataWriterFailure
import java.io.IOException
import java.util.WeakHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Construct once per page writer. The production factory must bind the exact DB's admitted store. */
internal class LanzhouMahjongHostStorage(
    val gate: LanzhouMahjongStorageEpochGate,
    createStore: (LanzhouMahjongStorageEpochGate.Owner) -> LanzhouMahjongAdmittedSessionStore,
) {
    private val owner = gate.captureOwner()
    val epoch: Long = owner.epoch
    val store: LanzhouMahjongAdmittedSessionStore = createStore(owner)

    /** Gate identity also fences Native lifetimes, without holding any database/backup lock. */
    suspend fun awaitPreviousHost(completion: Deferred<Unit>) {
        val slot = slotFor(gate)
        slot.mutex.withLock {
            slot.previous?.await()
            slot.previous = completion
        }
    }

    private class Slot {
        val mutex = Mutex()
        var previous: Deferred<Unit>? = null
    }

    companion object {
        private val slots = WeakHashMap<LanzhouMahjongStorageEpochGate, Slot>()
        @Synchronized private fun slotFor(gate: LanzhouMahjongStorageEpochGate): Slot =
            slots.getOrPut(gate) { Slot() }
    }
}

/** One mutex covers Native creation, reads, mutations, acknowledgements and disposal on the worker. */
internal class LanzhouMahjongGameHost(
    private val storage: LanzhouMahjongHostStorage,
    private val launchConfig: LanzhouMahjongLaunchConfig,
    private val sessionFactory: (LanzhouMahjongLaunchConfig) -> LanzhouMahjongBotSession = {
        NativeLanzhouMahjongSession(it.seed, it.dealerSeat, it.baseScore, it.viewerSeat)
    },
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val now: () -> Long = System::currentTimeMillis,
    private val newSeed: () -> Long = { System.nanoTime() and Long.MAX_VALUE },
) : LocalDataWriterRetirement {
    private val mutex = Mutex()
    private val cleanupScope = CoroutineScope(SupervisorJob() + dispatcher)
    private val completion = CompletableDeferred<Unit>()
    override val retirementCompletion: Deferred<Unit> = completion
    private val mutableState = MutableStateFlow(LanzhouMahjongGameUiState())
    val state: StateFlow<LanzhouMahjongGameUiState> = mutableState.asStateFlow()
    @Volatile private var retiring = false
    @Volatile private var leaving = false
    private var cleanupStarted = false
    private var opened = false
    private var loadFailed = false
    private var session: LanzhouMahjongBotSession? = null
    private var config = launchConfig
    private var nextChoiceSeed = launchConfig.seed
    private var assistance: LanzhouMahjongAssistanceManager? = null
    private var pending: LanzhouMahjongStoredSession? = null
    private var acknowledged: LanzhouMahjongStoredSession? = null
    private var needsAdvance = false
    private var blocked = false
    private var nativeCloseFailure: Throwable? = null
    private val diagnosticLock = Any()
    private val diagnosticFailures = mutableListOf<Throwable>()
    private val cleanupFailures = mutableListOf<Throwable>()
    /** Raw causes stay inside the host; no native, SQL, wall or file detail enters UI state. */
    val failures: List<Throwable> get() = synchronized(diagnosticLock) { diagnosticFailures.toList() }
    private var hint: LanzhouMahjongHint? = null
    private var pendingHint: LanzhouMahjongHint? = null
    private var feedback: String? = null
    private val epochWatcher = cleanupScope.launch {
        storage.gate.epoch.collect { if (it != storage.epoch) beginRetirement() }
    }

    suspend fun open() = operate {
        if (opened || loadFailed) return@operate
        try {
            storage.awaitPreviousHost(completion)
            if (retiring || leaving) return@operate
            checkCurrentOwner()
            // Even a requested new game must inspect the existing row before any replacement.
            val saved = storage.store.load()
            acknowledged = saved
            if (retiring || leaving) return@operate
            checkCurrentOwner()
            config = saved?.record?.let {
                LanzhouMahjongLaunchConfig(it.difficulty, it.seed, it.viewerSeat, it.dealerSeat, it.baseScore)
            } ?: launchConfig
            nextChoiceSeed = saved?.record?.nextChoiceSeed ?: config.seed
            assistance = LanzhouMahjongAssistanceManager(config.difficulty,
                saved?.record?.assistance ?: LanzhouMahjongAssistanceUsage(config.difficulty),
                saved?.record?.decisions ?: emptyList(), { nextChoiceSeed }, { nextChoiceSeed = it })
            val engine = sessionFactory(config)
            session = engine
            if (saved != null && (engine.restore(saved.record.nativeState) != RestoreResult.Restored ||
                    !engine.serialize().contentEquals(saved.record.nativeState))) {
                throw IOException("兰州麻将存档无法完整恢复，原数据已保留")
            }
            opened = true
            advanceAndSave()
        } catch (cancelled: CancellationException) {
            failOpen(cancelled, "对局恢复已中止，原存档已保留")
            throw cancelled
        } catch (failure: Exception) {
            failOpen(failure, "对局恢复失败，原存档已保留，请返回后重试")
        } catch (failure: LinkageError) {
            failOpen(failure, "兰州麻将规则引擎暂不可用，原存档已保留")
        }
    }

    private fun failOpen(failure: Throwable, message: String) {
        recordFailure(failure)
        loadFailed = true
        var releaseFailed = false
        try { closeNative() } catch (_: Throwable) {
            // Aggregate without adding suppressed exceptions to the original cause objects.
            cleanupFailures.add(0, failure)
            releaseFailed = true
        }
        mutableState.update { it.copy(isLoading = false, error = message, restoreFailed = true,
            retired = retiring, leaving = retiring) }
        if (releaseFailed) beginRetirement()
    }

    suspend fun apply(action: LanzhouMahjongAction) = operate {
        if (!playable()) return@operate
        val engine = checkNotNull(session)
        val actions = engine.legalActions()
        if (action !in actions) { feedback = "此时不能执行该操作"; return@operate }
        val manager = checkNotNull(assistance)
        val decision = manager.beforeAction(engine.serialize(), actions, action)
        when (engine.apply(action)) {
            ActionResult.Accepted -> {
                manager.accepted(decision)
                hint = null
                pendingHint = null
                advanceAndSave()
            }
            is ActionResult.Rejected -> feedback = "当前操作不合法"
        }
    }

    suspend fun retrySave() = operate { if (!blocked && pending != null) savePending() }
    suspend fun retryAdvance() = operate {
        if (opened && pending == null && needsAdvance && !blocked) advanceAndSave()
    }

    suspend fun requestHint() = operate {
        if (!playable()) return@operate
        val engine = checkNotNull(session)
        val manager = checkNotNull(assistance)
        val candidate = LanzhouMahjongHintPlanner.plan(engine.snapshot(), engine.viewerSeat,
            config.difficulty, engine.legalActions()) ?: return@operate
        if (!manager.useHint()) { feedback = "本局提示次数已用完"; return@operate }
        pendingHint = candidate
        hint = null
        checkpoint()
    }

    suspend fun undoDecision() = operate {
        if (!playable()) return@operate
        val engine = checkNotNull(session)
        val manager = checkNotNull(assistance)
        val plan = manager.undoPlan(engine.serialize()) ?: return@operate
        if (engine.restore(plan.nativeState) != RestoreResult.Restored ||
            !engine.serialize().contentEquals(plan.nativeState)) {
            blocked = true
            feedback = "撤回校验失败，原存档已保留，请返回后重新打开"
            return@operate
        }
        manager.acceptUndo(plan)
        hint = null
        pendingHint = null
        checkpoint()
    }

    suspend fun restart() = operate {
        if (!opened || loadFailed || pending != null || blocked) return@operate
        val dealer = checkNotNull(session).snapshot().outcome.nextDealer
        // Release the old handle before opening the next one, including when release fails.
        closeNative()
        config = config.copy(seed = newSeed(), dealerSeat = dealer)
        nextChoiceSeed = config.seed
        assistance = LanzhouMahjongAssistanceManager(config.difficulty,
            choiceSeed = { nextChoiceSeed }, restoreChoiceSeed = { nextChoiceSeed = it })
        hint = null
        pendingHint = null
        session = sessionFactory(config)
        advanceAndSave()
    }

    /** Keep the exact request (time, seed, usage and decisions) until the strict store acknowledges it. */
    private suspend fun checkpoint() {
        check(pending == null)
        val manager = checkNotNull(assistance)
        val record = LanzhouMahjongSessionRecord(config.difficulty,
            config.viewerSeat, config.seed, config.dealerSeat, config.baseScore, nextChoiceSeed,
            checkNotNull(session).serialize(), manager.usage, manager.decisions)
        val previous = acknowledged
        if (previous != null && LanzhouMahjongSessionRecordCodec.encode(previous.record)
                .contentEquals(LanzhouMahjongSessionRecordCodec.encode(record))) {
            feedback = if (needsAdvance) "对手操作暂停，已保存进度，可继续对局" else null
            return
        }
        pending = LanzhouMahjongStoredSession(record, now())
        savePending()
    }

    private suspend fun savePending() {
        val request = pending ?: return
        try {
            checkCurrentOwner()
            storage.store.save(request)
            acknowledged = request
            pending = null
            pendingHint?.let { hint = it; pendingHint = null }
            feedback = if (needsAdvance) "对手操作暂停，已保存进度，可继续对局" else null
        } catch (cancelled: CancellationException) {
            recordFailure(cancelled)
            feedback = "保存已中止，待保存对局已保留，请重试"
            throw cancelled
        } catch (failure: Exception) {
            recordFailure(failure)
            feedback = "对局保存失败，请重试"
            if (storage.gate.epoch.value != storage.epoch) beginRetirement()
        }
    }

    private suspend fun advanceAndSave() {
        val engine = checkNotNull(session)
        var checkpointFinished = false
        try {
            val before = engine.serialize()
            val count = LanzhouMahjongNativeHistory.actionCount(before)
                ?: throw IOException("无法测量对手操作")
            var result: LanzhouMahjongBotAdvance? = null
            var interruption: CancellationException? = null
            try {
                result = engine.advanceOtherSeats(config.difficulty, nextChoiceSeed)
            } catch (cancelled: CancellationException) {
                recordFailure(cancelled)
                interruption = cancelled
            } catch (failure: Exception) {
                recordFailure(failure)
                // Native commits each legal action atomically, but a batch may throw after a prefix.
            }
            val after = engine.serialize()
            val afterCount = LanzhouMahjongNativeHistory.actionCount(after)
                ?: throw IOException("无法测量对手操作")
            val delta = afterCount - count
            if (delta < 0 || !before.copyOfRange(0, 122).contentEquals(after.copyOfRange(0, 122)) ||
                !before.copyOfRange(126, 126 + count * 3).contentEquals(after.copyOfRange(126, 126 + count * 3)) ||
                (result != null && result.steps != delta)) throw IOException("对手操作历史不一致")
            nextChoiceSeed = (nextChoiceSeed + delta) and Long.MAX_VALUE
            needsAdvance = result?.stop !in listOf(LanzhouMahjongBotStop.VIEWER_ACTION, LanzhouMahjongBotStop.TERMINAL)
            checkpoint()
            checkpointFinished = true
            interruption?.let { throw it }
        } catch (cancelled: CancellationException) {
            recordFailure(cancelled)
            if (!checkpointFinished && pending == null) blockUnmeasuredProgress()
            throw cancelled
        } catch (failure: Exception) {
            recordFailure(failure)
            // Do not persist or reuse a seed when the complete committed prefix cannot be measured.
            blockUnmeasuredProgress()
        }
    }

    private fun blockUnmeasuredProgress() {
        blocked = true
        needsAdvance = false
        feedback = "对手操作无法确认，原存档已保留，请返回后重新打开"
    }

    private fun recordFailure(failure: Throwable) = synchronized(diagnosticLock) {
        if (diagnosticFailures.none { it === failure }) diagnosticFailures += failure
    }

    private fun playable() = opened && session != null && pending == null && !needsAdvance && !blocked
    private fun checkCurrentOwner() {
        // Accepted work may finish during page retirement; the fixed store owner still checks
        // under the gate. A changed epoch is never refreshed, including on failed restore.
        if (storage.gate.epoch.value != storage.epoch) {
            beginRetirement()
            throw IOException("本地数据已替换，请重新打开兰州麻将页面")
        }
    }

    private suspend fun operate(operation: suspend () -> Unit) {
        if (retiring || leaving) return
        withContext(dispatcher + NonCancellable) {
            if (!mutex.tryLock()) return@withContext
            try {
                if (retiring || leaving) return@withContext
                checkCurrentOwner()
                mutableState.update { it.copy(table = it.table?.copy(isBusy = true, actions = emptyList())) }
                operation()
            } catch (cancelled: CancellationException) {
                recordFailure(cancelled)
                throw cancelled
            } catch (failure: Exception) {
                failOperation(failure)
            } catch (failure: LinkageError) {
                failOperation(failure)
            } finally {
                try { publish() } finally { mutex.unlock() }
            }
        }
    }

    private fun failOperation(failure: Throwable) {
        recordFailure(failure)
        blocked = true
        feedback = "对局操作失败，原存档已保留，请返回后重试"
        mutableState.update { it.copy(isLoading = false, error = feedback,
            table = it.table?.copy(isBusy = false, actions = emptyList(), blocked = true)) }
    }

    private fun publish() {
        if (retiring || leaving || loadFailed || session == null) return
        if (nativeCloseFailure != null) {
            mutableState.update { it.copy(isLoading = false, error = "对局资源释放失败，请返回",
                table = it.table?.copy(isBusy = false, actions = emptyList(), blocked = true)) }
            return
        }
        val engine = checkNotNull(session)
        try {
            val table = LanzhouMahjongTableState(engine.snapshot(), engine.viewerSeat,
                    if (playable()) engine.legalActions() else emptyList(), feedback,
                    needsSave = pending != null, needsAdvance = needsAdvance,
                    assistance = assistance?.usage, canUndo = playable() && assistance?.canUndo == true,
                    hint = hint, blocked = blocked)
            synchronized(this) {
                if (!retiring && !leaving) mutableState.value = LanzhouMahjongGameUiState(isLoading = false, table = table)
            }
        } catch (failure: Exception) {
            recordFailure(failure)
            blocked = true
            mutableState.update { it.copy(isLoading = false,
                error = "无法读取对局，原存档已保留，请返回后重新打开",
                table = it.table?.copy(isBusy = false, actions = emptyList(), blocked = true)) }
        }
    }

    /** Normal exit acknowledges any exact pending request first; failure keeps the page and handle. */
    suspend fun leave(): Boolean = withContext(dispatcher + NonCancellable) {
        if (retiring) return@withContext false
        leaving = true
        mutableState.update { it.copy(leaving = true,
            table = it.table?.copy(isBusy = true, actions = emptyList())) }
        mutex.withLock {
            if (retiring) return@withLock false
            if (completion.isCompleted) { completion.await(); return@withLock true }
            try {
                if (!blocked && pending != null) savePending()
                if (pending != null || retiring) {
                    leaving = false
                    mutableState.update { it.copy(leaving = retiring) }
                    publish()
                    return@withLock false
                }
                closeNative()
                completion.complete(Unit)
                cleanupScope.cancel()
                mutableState.update { it.copy(leaving = true) }
                true
            } catch (cancelled: CancellationException) {
                recordFailure(cancelled)
                leaving = false
                mutableState.update { it.copy(leaving = retiring,
                    error = "返回操作已中止，当前对局已保留",
                    table = it.table?.copy(isBusy = false, actions = emptyList())) }
                throw cancelled
            } catch (failure: Throwable) {
                recordFailure(failure)
                leaving = false
                mutableState.update { it.copy(leaving = retiring,
                    error = "对局资源释放失败，请重试返回",
                    table = it.table?.copy(isBusy = false, blocked = true)) }
                false
            }
        }
    }

    private fun closeNative() {
        nativeCloseFailure?.let { throw it }
        val engine = session ?: return
        try { engine.close() } catch (failure: Throwable) {
            nativeCloseFailure = failure
            recordFailure(failure)
            cleanupFailures += failure
            throw failure
        }
        session = null
    }

    /** Retirement rejects work synchronously, waits accepted work, then disposes without refreshing owner. */
    @Synchronized override fun beginRetirement(): Deferred<Unit> {
        retiring = true
        leaving = true
        mutableState.update { it.copy(isLoading = false, retired = true, leaving = true,
            error = "本地数据已替换或页面已关闭，请重新打开兰州麻将",
            table = it.table?.copy(actions = emptyList(), isBusy = false, blocked = true)) }
        if (!cleanupStarted && !completion.isCompleted) {
            cleanupStarted = true
            cleanupScope.launch {
                try {
                    withContext(NonCancellable) {
                        mutex.withLock {
                            try { closeNative() } catch (failure: Throwable) {
                                recordFailure(failure)
                                if (cleanupFailures.none { it === failure }) cleanupFailures += failure
                            }
                        }
                    }
                    val failure = LocalDataWriterFailure.combine(cleanupFailures)
                    if (failure == null) completion.complete(Unit) else completion.completeExceptionally(failure)
                } catch (failure: Throwable) {
                    recordFailure(failure)
                    completion.completeExceptionally(failure)
                }
                finally { cleanupScope.cancel() }
            }
        }
        return completion
    }

    suspend fun closeAndJoin() { beginRetirement().await() }
}
