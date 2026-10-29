package com.masterofchessstrategy.games.lanzhou_mahjong

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.LanzhouMahjongAction
import com.masterofchessstrategy.engine.LanzhouMahjongBotSession
import com.masterofchessstrategy.engine.NativeLanzhouMahjongSession
import com.masterofchessstrategy.settings.LocalDataWriterRetirement
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow

internal data class LanzhouMahjongLaunchConfig(
    val difficulty: Difficulty,
    val seed: Long,
    val viewerSeat: Int = 0,
    val dealerSeat: Int = viewerSeat,
    val baseScore: Long = 1,
) {
    init {
        require(seed >= 0 && viewerSeat in 0..3 && dealerSeat in 0..3)
        require(baseScore in 1..Long.MAX_VALUE / 96)
    }
}

internal enum class LanzhouMahjongOpenMode { NEW, RESUME }

internal data class LanzhouMahjongGameUiState(
    val table: LanzhouMahjongTableState? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
    val restoreFailed: Boolean = false,
    val leaving: Boolean = false,
    val retired: Boolean = false,
)

/** The route waits for leave; shared writer registration also waits for retirementCompletion. */
internal class LanzhouMahjongGameViewModel(
    storage: LanzhouMahjongHostStorage,
    launchConfig: LanzhouMahjongLaunchConfig,
    sessionFactory: (LanzhouMahjongLaunchConfig) -> LanzhouMahjongBotSession = {
        NativeLanzhouMahjongSession(it.seed, it.dealerSeat, it.baseScore, it.viewerSeat)
    },
    newSeed: () -> Long = { System.nanoTime() and Long.MAX_VALUE },
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    now: () -> Long = System::currentTimeMillis,
    openMode: LanzhouMahjongOpenMode = LanzhouMahjongOpenMode.NEW,
) : ViewModel(), LocalDataWriterRetirement {
    private val host = LanzhouMahjongGameHost(storage, launchConfig, sessionFactory, dispatcher, now, newSeed, openMode)
    val state: StateFlow<LanzhouMahjongGameUiState> = host.state
    val failures: List<Throwable> get() = host.failures
    private var actionJob: Job? = null
    override val retirementCompletion: Deferred<Unit> get() = host.retirementCompletion

    init { actionJob = viewModelScope.launch { host.open() } }

    fun apply(action: LanzhouMahjongAction) = runAction { host.apply(action) }
    fun retrySave() = runAction { host.retrySave() }
    fun retryAdvance() = runAction { host.retryAdvance() }
    fun requestHint() = runAction { host.requestHint() }
    fun undoDecision() = runAction { host.undoDecision() }
    fun restart() = runAction { host.restart() }

    private fun runAction(action: suspend () -> Unit) {
        if (actionJob?.isActive == true) return
        actionJob = viewModelScope.launch { action() }
    }

    /** Cleanup is NonCancellable inside the host, even if this route waiter is disposed. */
    suspend fun leave(): Boolean = if (state.value.retired) {
        try { retirementCompletion.await(); true } catch (_: Exception) { false }
    } else host.leave()
    override fun beginRetirement(): Deferred<Unit> = host.beginRetirement()
    override fun onCleared() { beginRetirement(); super.onCleared() }
}
