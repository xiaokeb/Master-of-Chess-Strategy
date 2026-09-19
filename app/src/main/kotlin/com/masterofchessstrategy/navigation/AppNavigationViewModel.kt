package com.masterofchessstrategy.navigation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.masterofchessstrategy.data.LastGameSelection
import com.masterofchessstrategy.data.LastSelectionRepository
import com.masterofchessstrategy.data.LoadLastSelectionResult
import com.masterofchessstrategy.data.StoredGameMode
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.GameType
import kotlinx.coroutines.launch

internal data class AppNavigationUiState(
    val isLoadingSelection: Boolean = true,
    val lastChineseChessSelection: LastGameSelection? = null,
)

internal enum class QuickStartDestination {
    MODE_SELECTION,
    DIFFICULTY,
    TUTORIAL,
    AI_GAME,
    AUTO_PLAY_GAME,
    ENDGAME_CATALOG,
    CUSTOM_SETUP,
    GAME,
}

internal fun LastGameSelection.quickStartDestination(): QuickStartDestination =
    when (mode) {
        StoredGameMode.LOCAL_TWO_PLAYER -> QuickStartDestination.GAME
        StoredGameMode.HUMAN_VS_AI -> if (difficulty == null) {
            QuickStartDestination.DIFFICULTY
        } else {
            QuickStartDestination.AI_GAME
        }
        StoredGameMode.TUTORIAL -> QuickStartDestination.TUTORIAL
        StoredGameMode.AI_AUTO_PLAY -> if (difficulty == null) {
            QuickStartDestination.MODE_SELECTION
        } else {
            QuickStartDestination.AUTO_PLAY_GAME
        }

        StoredGameMode.ENDGAME -> QuickStartDestination.ENDGAME_CATALOG
        StoredGameMode.CUSTOM_POSITION -> QuickStartDestination.CUSTOM_SETUP
    }

/**
 * Owns app-level navigation metadata so it survives destination changes.
 */
internal class AppNavigationViewModel(
    private val repository: LastSelectionRepository,
    private val nowEpochMillis: () -> Long = { System.currentTimeMillis() },
) : ViewModel() {
    private var selectionRevision = 0

    var uiState by mutableStateOf(AppNavigationUiState())
        private set

    init {
        loadChineseChessSelection()
    }

    fun recordChineseChessSelection(
        mode: StoredGameMode,
        difficulty: Difficulty? = null,
    ) {
        selectionRevision++
        val selection = LastGameSelection(
            gameType = GameType.CHINESE_CHESS,
            mode = mode,
            difficulty = difficulty,
            updatedAtEpochMillis = nowEpochMillis(),
        )
        viewModelScope.launch {
            try {
                repository.save(selection)
                uiState = AppNavigationUiState(
                    isLoadingSelection = false,
                    lastChineseChessSelection = selection,
                )
            } catch (_: RuntimeException) {
                // Navigation still proceeds; failed metadata must not block play.
                uiState = uiState.copy(isLoadingSelection = false)
            }
        }
    }

    fun chineseChessQuickStartDestination(): QuickStartDestination? =
        if (uiState.isLoadingSelection) {
            null
        } else {
            uiState.lastChineseChessSelection?.quickStartDestination()
        }

    private fun loadChineseChessSelection() {
        val loadRevision = selectionRevision
        viewModelScope.launch {
            val result = try {
                repository.load(GameType.CHINESE_CHESS)
            } catch (_: RuntimeException) {
                uiState = AppNavigationUiState(isLoadingSelection = false)
                return@launch
            }
            if (loadRevision != selectionRevision) {
                return@launch
            }
            when (result) {
                LoadLastSelectionResult.NotFound -> {
                    uiState = AppNavigationUiState(isLoadingSelection = false)
                }

                LoadLastSelectionResult.Incompatible -> {
                    clearIncompatibleSelection()
                    uiState = AppNavigationUiState(isLoadingSelection = false)
                }

                is LoadLastSelectionResult.Loaded -> {
                    uiState = AppNavigationUiState(
                        isLoadingSelection = false,
                        lastChineseChessSelection = result.selection,
                    )
                }
            }
        }
    }

    private suspend fun clearIncompatibleSelection() {
        try {
            repository.clear(GameType.CHINESE_CHESS)
        } catch (_: RuntimeException) {
            // A stale row is harmless because it is never exposed to the UI.
        }
    }

    companion object {
        fun factory(repository: LastSelectionRepository): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    AppNavigationViewModel(repository)
                }
            }
    }
}
