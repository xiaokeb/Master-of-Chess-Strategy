package com.masterofchessstrategy.games.competition_mahjong.learning

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.masterofchessstrategy.settings.LocalDataWriterRetirement
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

internal class CompetitionMahjongLearningViewModel(
    store: GatedCompetitionMahjongLearningProofStore,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    now: () -> Long = System::currentTimeMillis,
) : ViewModel(), LocalDataWriterRetirement {
    private val controller = CompetitionMahjongLearningController(store, dispatcher, now,
        ownerIsCurrent = { store.epoch.value == store.ownerEpoch })
    val state = controller.state
    internal val lastFailure: Throwable? get() = controller.lastFailure
    override val retirementCompletion: Deferred<Unit> get() = controller.retirementCompletion
    private var epochWatcher: Job? = null

    init {
        load()
        epochWatcher = viewModelScope.launch {
            store.epoch.collect { if (it != store.ownerEpoch) beginRetirement() }
        }
    }

    fun load() { viewModelScope.launch { controller.load() } }
    fun selectChapter(index: Int) { viewModelScope.launch { controller.selectChapter(index) } }
    fun acknowledgeReading() { viewModelScope.launch { controller.acknowledgeReading() } }
    fun selectOption(index: Int) { viewModelScope.launch { controller.selectOption(index) } }
    fun toggleTile(index: Int) { viewModelScope.launch { controller.toggleTile(index) } }
    fun submit() { viewModelScope.launch { controller.submit() } }
    fun advance() { viewModelScope.launch { controller.advance() } }
    fun retrySave() { viewModelScope.launch { controller.retrySave() } }

    override fun beginRetirement(): Deferred<Unit> {
        val completion = controller.beginRetirement()
        epochWatcher?.cancel()
        return completion
    }

    override fun onCleared() { beginRetirement(); super.onCleared() }
}
