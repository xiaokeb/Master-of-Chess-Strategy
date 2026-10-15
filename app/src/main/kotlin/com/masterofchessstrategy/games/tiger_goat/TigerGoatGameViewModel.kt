package com.masterofchessstrategy.games.tiger_goat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

internal data class TigerGoatPageState(
    val boardSpec: TigerGoatBoardSpec? = null,
    val game: TigerGoatDisplayState? = null,
    val busy: Boolean = true,
    val error: String? = null,
    val canRetrySave: Boolean = false,
    val recoveryFailed: Boolean = false,
)

/** Local two-player lifecycle: load first, persist every rule mutation, then unlock input. */
internal class TigerGoatGameViewModel(
    private val store: TigerGoatSessionStore,
    private val sessionFactory: () -> TigerGoatRulesSession,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : ViewModel() {
    private val state = MutableStateFlow(TigerGoatPageState())
    val uiState: StateFlow<TigerGoatPageState> = state
    private var controller: TigerGoatGameController? = null
    private var session: TigerGoatRulesSession? = null
    private var sessionId = ""
    private var pendingSave: TigerGoatSavedSession? = null

    init { load() }

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
        if (state.value.busy || pendingSave == null) return
        publish(busy = true)
        viewModelScope.launch { savePending() }
    }

    fun retryLoad() {
        if (state.value.busy || !state.value.recoveryFailed) return
        load()
    }

    /** The route must obtain explicit restart confirmation before replacing a failed save. */
    fun restartAfterRecoveryFailure() {
        if (state.value.busy || !state.value.recoveryFailed || controller == null) return
        try {
            controller!!.restart()
            sessionId = newId()
            pendingSave = savedState()
            publish(busy = true)
            viewModelScope.launch { savePending() }
        } catch (_: Exception) {
            recoveryError()
        }
    }

    private fun load() {
        publish(busy = true)
        viewModelScope.launch {
            try {
                controller?.close()
                controller = null
                session = null
                val rules = sessionFactory()
                session = rules
                val current = TigerGoatGameController(rules)
                controller = current
                when (val result = store.load()) {
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
                throw cancelled
            } catch (_: Exception) {
                recoveryError()
            } catch (_: LinkageError) {
                publish(error = "规则引擎暂不可用，请重试", recoveryFailed = true)
            }
        }
    }

    private fun perform(operation: (TigerGoatGameController) -> Unit) {
        if (state.value.busy || state.value.error != null) return
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
                viewModelScope.launch { savePending() }
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
        val saved = pendingSave ?: return
        try {
            store.save(saved.copyOwned())
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

    override fun onCleared() {
        controller?.close()
        controller = null
        session = null
        super.onCleared()
    }
}
