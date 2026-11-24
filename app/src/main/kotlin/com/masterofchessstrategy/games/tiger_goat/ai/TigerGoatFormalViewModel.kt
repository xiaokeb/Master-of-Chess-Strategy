package com.masterofchessstrategy.games.tiger_goat.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.masterofchessstrategy.data.MocsDatabase
import com.masterofchessstrategy.engine.games.tiger_goat.NativeTigerGoatSession
import com.masterofchessstrategy.games.tiger_goat.NativeTigerGoatRulesSession
import com.masterofchessstrategy.games.tiger_goat.TigerGoatAiSearchCoordinator
import com.masterofchessstrategy.games.tiger_goat.TigerGoatTutorialProgress
import com.masterofchessstrategy.games.tiger_goat.TigerGoatTutorialStep
import com.masterofchessstrategy.games.tiger_goat.data.TigerGoatStorageEpochGate
import com.masterofchessstrategy.games.tiger_goat.data.roomTigerGoatFormalStore
import com.masterofchessstrategy.settings.LocalDataWriterRetirement
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal class TigerGoatFormalViewModel(database: MocsDatabase) : ViewModel(), LocalDataWriterRetirement {
    private val gate = TigerGoatStorageEpochGate.forDatabase(database)
    private val epoch = gate.epoch.value
    private val progress = TigerGoatTutorialProgress(database)
    private var qualified = false
    private val owner = TigerGoatFormalGameOwner(
        roomTigerGoatFormalStore(database),
        NativeTigerGoatRulesSession(NativeTigerGoatSession()),
        CoordinatedTigerGoatFormalSearch(TigerGoatAiSearchCoordinator()),
        TigerGoatFormalConfig(humanSide = 1),
        isQualified = { qualified },
    )
    val uiState = owner.uiState
    private val retirement = CompletableDeferred<Unit>()
    override val retirementCompletion get() = retirement
    private var leaving = false
    private var operation: Job? = null

    init {
        retryLoad()
        viewModelScope.launch { gate.epoch.collect { if (it != epoch) beginRetirement() } }
    }

    fun tap(point: Int) = operate { onPointTap(point) }
    fun endChain() = operate { endChain() }
    fun passGoat() = operate { passGoat() }
    fun resign() = operate { resign() }
    fun undo() = operate { undo() }
    fun restart() = operate { restart() }
    fun retrySave() = operate { retrySave() }
    fun retryLoad() = operate {
        qualified = try {
            progress.load() == TigerGoatTutorialStep.entries.size
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        load()
    }
    fun retryAi() = operate { retryAi() }
    fun advance() { if (uiState.value.thinking) operate { advanceAi() } }

    private fun operate(action: suspend TigerGoatFormalGameOwner.() -> Unit) {
        if (leaving || operation?.isActive == true) return
        operation = viewModelScope.launch { owner.action() }
    }

    override fun beginRetirement(): CompletableDeferred<Unit> {
        if (leaving) return retirement
        leaving = true
        owner.beginRetirement()
        // Native search must be joined even when navigation has cancelled the VM scope.
        CoroutineScope(Dispatchers.Main.immediate).launch {
            try {
                owner.retire()
                retirement.complete(Unit)
            } catch (failure: Throwable) {
                retirement.completeExceptionally(failure)
            }
        }
        return retirement
    }

    override fun onCleared() { beginRetirement() }
}
