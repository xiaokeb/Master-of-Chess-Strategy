package com.masterofchessstrategy.games.tiger_goat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.masterofchessstrategy.games.tiger_goat.data.GatedTigerGoatSessionStore
import com.masterofchessstrategy.settings.LocalDataWriterRetirement
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class TigerGoatPageState(
    val boardSpec: TigerGoatBoardSpec? = null,
    val game: TigerGoatDisplayState? = null,
    val busy: Boolean = true,
    val error: String? = null,
    val canRetrySave: Boolean = false,
    val recoveryFailed: Boolean = false,
    val retired: Boolean = false,
    val retiring: Boolean = false,
)

/** Local two-player lifecycle: load first, persist every rule mutation, then unlock input. */
internal class TigerGoatGameViewModel(
    private val store: TigerGoatSessionStore,
    private val sessionFactory: () -> TigerGoatRulesSession,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : ViewModel(), LocalDataWriterRetirement {
    private val state = MutableStateFlow(TigerGoatPageState())
    val uiState: StateFlow<TigerGoatPageState> = state
    // Production passes one fixed-owner wrapper for this VM; retries never acquire a new owner.
    private val ownedStore = store as? GatedTigerGoatSessionStore
    private val operations = SupervisorJob(viewModelScope.coroutineContext[Job])
    private val operationScope = CoroutineScope(viewModelScope.coroutineContext + operations)
    private val retirement = CompletableDeferred<Unit>()
    override val retirementCompletion: Deferred<Unit> get() = retirement
    private var retirementStarted = false
    private var retirementNotice: String? = null
    private var epochWatcher: Job? = null
    private var nativeCloseFailure: Throwable? = null
    private var controller: TigerGoatGameController? = null
    private var session: TigerGoatRulesSession? = null
    private var sessionId = ""
    private var pendingSave: TigerGoatSavedSession? = null

    init {
        ownedStore?.let { guarded ->
            epochWatcher = viewModelScope.launch { guarded.epoch.collect { acceptsWork() } }
        }
        load()
    }

    fun onPointTap(point: Int) = perform { it.onPointTap(point) }
    fun endChain() = perform { it.endChain() }
    fun passGoat() = perform { it.passGoat() }
    fun undo() = perform { it.undo() }
    fun resign() = perform { it.resign() }
    fun restart() = perform {
        it.restart()
        sessionId = newId()
    }

    fun retrySave() {
        if (!acceptsWork() || state.value.busy || pendingSave == null) return
        publish(busy = true)
        operationScope.launch { savePending() }
    }

    fun retryLoad() {
        if (!acceptsWork() || state.value.busy || !state.value.recoveryFailed) return
        load()
    }

    /** The route must obtain explicit restart confirmation before replacing a failed save. */
    fun restartAfterRecoveryFailure() {
        if (!acceptsWork() || state.value.busy || !state.value.recoveryFailed || controller == null) return
        try {
            controller!!.restart()
            sessionId = newId()
            pendingSave = savedState()
            publish(busy = true)
            operationScope.launch { savePending() }
        } catch (_: Exception) {
            recoveryError()
        }
    }

    private fun load() {
        if (!acceptsWork()) return
        publish(busy = true)
        operationScope.launch {
            try {
                if (!acceptsWork()) return@launch
                closeSession()
                val rules = sessionFactory()
                session = rules
                val current = TigerGoatGameController(rules)
                controller = current
                val result = store.load()
                // A gate notification may still be queued after load released its lock.
                if (!acceptsWork()) return@launch
                when (result) {
                    TigerGoatLoadResult.Missing -> {
                        sessionId = newId()
                        pendingSave = savedState()
                        savePending()
                    }
                    TigerGoatLoadResult.Incompatible -> recoveryError()
                    is TigerGoatLoadResult.Loaded -> {
                        if (!current.restore(result.saved.bytes) || rules.actionCount != result.saved.actionCount) {
                            recoveryError()
                        } else {
                            sessionId = result.saved.id
                            publish()
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                if (nativeCloseFailure != null) beginRetirement() else throw cancelled
            } catch (_: Exception) {
                if (nativeCloseFailure != null) beginRetirement() else recoveryError()
            } catch (_: LinkageError) {
                if (nativeCloseFailure != null) beginRetirement()
                else publish(error = "规则引擎暂不可用，请重试", recoveryFailed = true)
            }
        }
    }

    private fun perform(operation: (TigerGoatGameController) -> Unit) {
        if (!acceptsWork() || state.value.busy || state.value.error != null) return
        val current = controller ?: return
        val before = try { current.serialize() } catch (_: Exception) {
            publish(error = "对局操作失败，请重新载入", recoveryFailed = true)
            return
        }
        val beforeId = sessionId
        try {
            operation(current)
            if (!before.contentEquals(current.serialize()) || beforeId != sessionId) {
                pendingSave = savedState()
                publish(busy = true)
                operationScope.launch { savePending() }
            } else publish()
        } catch (_: Exception) {
            runCatching { current.restore(before) }
            sessionId = beforeId
            publish(error = "对局操作失败，请重新载入", recoveryFailed = true)
        }
    }

    private fun savedState() = TigerGoatSavedSession(
        sessionId, controller!!.serialize(), session!!.actionCount, now(),
    )

    private suspend fun savePending() {
        if (!acceptsWork()) return
        val saved = pendingSave ?: return
        try {
            store.save(saved.copyOwned())
            if (!acceptsWork()) return
            pendingSave = null
            publish()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            publish(error = "存档保存失败，请重试后继续", canRetrySave = true)
        }
    }

    private fun recoveryError() = publish(error = "存档无法恢复，请重试载入或确认重开", recoveryFailed = true)

    private fun publish(
        busy: Boolean = false,
        error: String? = null,
        canRetrySave: Boolean = false,
        recoveryFailed: Boolean = false,
    ) {
        if (!acceptsWork()) return
        val locked = busy || error != null
        state.value = TigerGoatPageState(
            boardSpec = controller?.boardSpec,
            game = controller?.uiState?.value?.copy(busy = locked),
            busy = busy,
            error = error,
            canRetrySave = canRetrySave,
            recoveryFailed = recoveryFailed,
        )
    }

    /** All controls and publication run on Main; the flow is an early signal, not the only fence. */
    private fun acceptsWork(): Boolean {
        if (retirementStarted) return false
        if (ownedStore != null && ownedStore.epoch.value != ownedStore.ownerEpoch) {
            retirementNotice = "本地数据已替换，请返回后重新打开虎羊棋"
            beginRetirement()
            return false
        }
        return true
    }

    private fun closeSession() {
        nativeCloseFailure?.let { throw it }
        val live = session ?: return
        try { live.close() } catch (failure: Throwable) {
            // A failed close has unknown ownership. Preserve it and never blindly retry release.
            nativeCloseFailure = failure
            throw failure
        }
        controller = null
        session = null
    }

    /** Synchronously seals admission; cleanup outlives cancellation of the ViewModel scope. */
    override fun beginRetirement(): Deferred<Unit> {
        if (retirementStarted) return retirement
        retirementStarted = true
        state.value = state.value.copy(busy = true, error = retirementNotice,
            canRetrySave = false, recoveryFailed = false, retired = true, retiring = true,
            game = state.value.game?.copy(busy = true))
        epochWatcher?.cancel()
        operations.cancel()
        val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        cleanup.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                withContext(NonCancellable) {
                    operations.cancelAndJoin()
                    closeSession()
                }
                pendingSave = null
                state.value = state.value.copy(busy = false, retiring = false)
                retirement.complete(Unit)
            } catch (failure: Throwable) {
                state.value = state.value.copy(busy = false, retiring = false,
                    error = "对局资源释放失败，暂时无法安全返回")
                retirement.completeExceptionally(failure)
            } finally { cleanup.cancel() }
        }
        return retirement
    }

    /** Navigation may leave only after accepted storage work and Native release have completed. */
    suspend fun leave(): Boolean = try {
        beginRetirement().await()
        true
    } catch (_: Throwable) {
        currentCoroutineContext().ensureActive()
        false
    }

    override fun onCleared() {
        beginRetirement()
        super.onCleared()
    }
}
