package com.masterofchessstrategy.games.competition_mahjong.statistics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.masterofchessstrategy.games.competition_mahjong.match.CompetitionMahjongMatchCheckpoint
import com.masterofchessstrategy.games.competition_mahjong.match.policy.CompetitionMahjongPlayPolicy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class CompetitionMahjongStatisticsUiState(
    val statistics: CompetitionMahjongStatistics = CompetitionMahjongStatistics.empty(),
    val loading: Boolean = true,
    val closed: Boolean = false,
    val notice: String? = null,
    val error: String? = null,
)

/** Idempotent bridge for the formal match completion transaction. */
class CompetitionMahjongStatisticsRecorder(
    private val store: CompetitionMahjongStatisticsStore,
    private val playPolicy: CompetitionMahjongPlayPolicy,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun record(checkpoint: CompetitionMahjongMatchCheckpoint): CompetitionMahjongRecordStatisticsResult =
        store.record(CompetitionMahjongSeriesOutcome.from(checkpoint, playPolicy, clockMillis()))

    suspend fun withdraw(checkpoint: CompetitionMahjongMatchCheckpoint): CompetitionMahjongStatistics {
        require(!checkpoint.ledger.isComplete)
        playPolicy.requireAuthorized(checkpoint.config.botLevel, checkpoint.assistance)
        return store.withdraw(checkpoint.config.viewerPlayerId, checkpoint.config.matchId)
    }
}

class CompetitionMahjongStatisticsViewModel(
    private val store: CompetitionMahjongStatisticsStore,
    private val viewerPlayerId: Int,
    parentScope: CoroutineScope? = null,
    private val worker: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val scope = parentScope ?: viewModelScope
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(CompetitionMahjongStatisticsUiState())
    val state = mutableState.asStateFlow()
    private var closed = false
    private val lifetime = scope.launch {
        try { awaitCancellation() }
        finally { withContext(NonCancellable) { mutex.withLock {
            closed = true
            mutableState.value = mutableState.value.copy(loading = false, closed = true)
        } } }
    }

    init {
        require(viewerPlayerId in 0..3)
        refresh()
    }

    fun refresh(): Job = scope.launch {
        withContext(worker + NonCancellable) {
            mutex.withLock {
                if (closed) return@withLock
                mutableState.value = mutableState.value.copy(loading = true, notice = null, error = null)
                try {
                    val statistics = store.load(viewerPlayerId)
                    require(statistics.outcomes.all { it.viewerPlayerId == viewerPlayerId })
                    mutableState.value = CompetitionMahjongStatisticsUiState(statistics, loading = false)
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    mutableState.value = mutableState.value.copy(loading = false,
                        error = "国标麻将统计无法读取，原数据已保留")
                }
            }
        }
    }

    internal fun record(checkpoint: CompetitionMahjongMatchCheckpoint,
                        recorder: CompetitionMahjongStatisticsRecorder): Job = scope.launch {
        withContext(worker + NonCancellable) {
            mutex.withLock {
                if (closed) return@withLock
                mutableState.value = mutableState.value.copy(loading = true, notice = null, error = null)
                try {
                    val result = recorder.record(checkpoint)
                    require(result.statistics.outcomes.all { it.viewerPlayerId == viewerPlayerId })
                    mutableState.value = CompetitionMahjongStatisticsUiState(result.statistics, loading = false,
                        notice = if (result.wasRecorded) "赛事统计已记录" else "该赛事已经记录")
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    mutableState.value = mutableState.value.copy(loading = false,
                        error = "赛事已结束，但统计尚未写入；可重试，不会重复计数")
                }
            }
        }
    }
}
