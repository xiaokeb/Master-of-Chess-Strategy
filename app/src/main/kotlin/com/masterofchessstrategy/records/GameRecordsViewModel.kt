package com.masterofchessstrategy.records

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.masterofchessstrategy.data.GameRecord
import com.masterofchessstrategy.data.GameRecordCategory
import com.masterofchessstrategy.data.GameRecordRepository
import com.masterofchessstrategy.data.LoadGameRecordsResult
import kotlinx.coroutines.launch

internal enum class GameRecordsFeedback {
    LOAD_RECOVERED,
    SAVE_FAILED,
    FAVORITE_FAILED,
}

internal data class GameRecordsUiState(
    val category: GameRecordCategory = GameRecordCategory.ALL,
    val records: List<GameRecord> = emptyList(),
    val isLoading: Boolean = true,
    val feedback: GameRecordsFeedback? = null,
)

internal class GameRecordsViewModel(
    private val repository: GameRecordRepository,
    private val highlightMaskProvider: () -> Int = { 0 },
) : ViewModel() {
    var uiState by mutableStateOf(GameRecordsUiState())
        private set

    init {
        reload()
    }

    fun record(record: GameRecord) {
        viewModelScope.launch {
            try {
                val autoFavorite = ChineseChessHighlightPolicy.matches(
                    record,
                    highlightMaskProvider(),
                )
                repository.saveCompleted(record.copy(isFavorite = record.isFavorite || autoFavorite))
                load(uiState.category)
            } catch (_: RuntimeException) {
                uiState = uiState.copy(feedback = GameRecordsFeedback.SAVE_FAILED)
            }
        }
    }

    fun selectCategory(category: GameRecordCategory) {
        if (category == uiState.category && !uiState.isLoading) return
        uiState = uiState.copy(category = category, isLoading = true, feedback = null)
        reload()
    }

    fun toggleFavorite(recordId: String) {
        val record = uiState.records.firstOrNull { it.recordId == recordId } ?: return
        viewModelScope.launch {
            try {
                if (!repository.setFavorite(recordId, !record.isFavorite)) {
                    uiState = uiState.copy(feedback = GameRecordsFeedback.FAVORITE_FAILED)
                } else {
                    load(uiState.category)
                }
            } catch (_: RuntimeException) {
                uiState = uiState.copy(feedback = GameRecordsFeedback.FAVORITE_FAILED)
            }
        }
    }

    internal fun reload() {
        val category = uiState.category
        viewModelScope.launch { load(category) }
    }

    private suspend fun load(category: GameRecordCategory) {
        uiState = try {
            when (val result = repository.list(category)) {
                is LoadGameRecordsResult.Loaded -> GameRecordsUiState(
                    category = category,
                    records = result.records,
                    isLoading = false,
                )

                LoadGameRecordsResult.Incompatible -> GameRecordsUiState(
                    category = category,
                    isLoading = false,
                    feedback = GameRecordsFeedback.LOAD_RECOVERED,
                )
            }
        } catch (_: RuntimeException) {
            GameRecordsUiState(
                category = category,
                isLoading = false,
                feedback = GameRecordsFeedback.LOAD_RECOVERED,
            )
        }
    }

    companion object {
        fun factory(
            repository: GameRecordRepository,
            highlightMaskProvider: () -> Int = { 0 },
        ): ViewModelProvider.Factory =
            viewModelFactory {
                initializer { GameRecordsViewModel(repository, highlightMaskProvider) }
            }
    }
}
