package com.masterofchessstrategy.games.lanzhou_mahjong

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.masterofchessstrategy.games.lanzhou_mahjong.data.LanzhouMahjongLearningRepository
import com.masterofchessstrategy.settings.LocalDataWriterRetirement
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

internal class LanzhouMahjongLearningViewModel(
    repository: LanzhouMahjongLearningRepository,
    worker: CoroutineDispatcher = Dispatchers.IO,
    now: () -> Long = System::currentTimeMillis,
) : ViewModel(), LocalDataWriterRetirement {
    private val controller = LanzhouMahjongLearningController(repository, worker, now)
    val state = controller.state
    internal val lastFailure: Throwable? get() = controller.lastFailure
    private var job: Job? = null
    private var epochWatcher: Job? = null
    override val retirementCompletion: Deferred<Unit> get() = controller.retirementCompletion
    init {
        reload()
        epochWatcher = viewModelScope.launch {
            repository.epoch.collect { if (it != repository.ownerEpoch) beginRetirement() }
        }
    }
    fun reload() = launchOperation { controller.load() }
    fun selectLesson(index: Int) = launchOperation { controller.selectLesson(index) }
    fun submit(index: Int) = launchOperation { controller.submit(index) }
    fun retrySave() = launchOperation { controller.retrySave() }
    private fun launchOperation(operation: suspend () -> Unit) {
        if (state.value.retired || job?.isActive == true) return
        job = viewModelScope.launch { operation() }
    }
    override fun beginRetirement(): Deferred<Unit> {
        val completion = controller.beginRetirement()
        epochWatcher?.cancel()
        return completion
    }
    override fun onCleared() { beginRetirement(); super.onCleared() }
}
