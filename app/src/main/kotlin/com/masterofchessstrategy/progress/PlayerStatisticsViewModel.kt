package com.masterofchessstrategy.progress

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.masterofchessstrategy.data.LoadMatchStatisticsResult
import com.masterofchessstrategy.data.MatchOutcome
import com.masterofchessstrategy.data.MatchStatisticsRepository
import com.masterofchessstrategy.data.PlayerStatistics
import kotlinx.coroutines.launch

internal enum class PlayerStatisticsFeedback {
    LOAD_FAILED,
    RECORD_FAILED,
}

internal data class PlayerStatisticsUiState(
    val statistics: PlayerStatistics = PlayerStatistics.EMPTY,
    val isLoading: Boolean = true,
    val feedback: PlayerStatisticsFeedback? = null,
)

/** App-scoped owner for the local, idempotent completed-match ledger. */
internal class PlayerStatisticsViewModel(
    private val repository: MatchStatisticsRepository,
) : ViewModel() {
    private val pendingMatchIds = mutableSetOf<String>()

    var uiState by mutableStateOf(PlayerStatisticsUiState())
        private set

    init {
        loadStatistics()
    }

    fun record(outcome: MatchOutcome) {
        if (!pendingMatchIds.add(outcome.matchId)) return
        viewModelScope.launch {
            try {
                val result = repository.record(outcome)
                uiState = PlayerStatisticsUiState(
                    statistics = result.statistics,
                    isLoading = false,
                )
            } catch (_: RuntimeException) {
                uiState = uiState.copy(
                    isLoading = false,
                    feedback = PlayerStatisticsFeedback.RECORD_FAILED,
                )
            } finally {
                pendingMatchIds.remove(outcome.matchId)
            }
        }
    }

    private fun loadStatistics() {
        viewModelScope.launch {
            uiState = try {
                when (val result = repository.load()) {
                    LoadMatchStatisticsResult.Incompatible -> {
                        PlayerStatisticsUiState(
                            isLoading = false,
                            feedback = PlayerStatisticsFeedback.LOAD_FAILED,
                        )
                    }

                    is LoadMatchStatisticsResult.Loaded -> {
                        PlayerStatisticsUiState(
                            statistics = result.statistics,
                            isLoading = false,
                        )
                    }
                }
            } catch (_: RuntimeException) {
                PlayerStatisticsUiState(
                    isLoading = false,
                    feedback = PlayerStatisticsFeedback.LOAD_FAILED,
                )
            }
        }
    }

    companion object {
        fun factory(repository: MatchStatisticsRepository): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    PlayerStatisticsViewModel(repository)
                }
            }
    }
}
