package com.masterofchessstrategy.games.go

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

internal interface GoDifficultyEligibilityRepository {
    val isCurrent: Boolean
    suspend fun load(): GoDifficultyEligibility
}

internal data class GoDifficultyProgressState(
    val loading: Boolean = true,
    val eligibility: GoDifficultyEligibility? = null,
    val error: String? = null,
)

/** Reload drops old eligibility immediately; a retired request cannot unlock a newer page. */
internal class GoDifficultyProgressViewModel(
    private val repository: GoDifficultyEligibilityRepository,
    private val worker: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val mutableState = MutableStateFlow(GoDifficultyProgressState())
    val state = mutableState.asStateFlow()
    private var request: Job? = null
    private var generation = 0L
    private var disposed = false
    private var hasResumed = false

    init { reload() }

    fun resume() {
        // Only the initial resume may reuse the constructor's read. A return from a lesson
        // must cancel even an unfinished older read, which may have captured pre-lesson data.
        if (hasResumed || !mutableState.value.loading) reload()
        hasResumed = true
    }

    fun reload() {
        if (disposed) return
        val current = ++generation
        request?.cancel()
        mutableState.value = GoDifficultyProgressState()
        request = viewModelScope.launch {
            try {
                val facts = withContext(worker) { repository.load() }
                ensureActive()
                // Restore can advance the epoch after SQL returns but before Main publishes it.
                if (!repository.isCurrent) throw IOException("本地数据已替换，请重新打开围棋页面")
                if (!disposed && generation == current) {
                    mutableState.value = GoDifficultyProgressState(loading = false, eligibility = facts)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (!disposed && generation == current) {
                    mutableState.value = GoDifficultyProgressState(loading = false,
                        error = error.message ?: "围棋解锁进度读取失败，请重试")
                }
            }
        }
    }

    override fun onCleared() {
        disposed = true
        generation += 1
        request?.cancel()
        super.onCleared()
    }
}
