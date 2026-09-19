package com.masterofchessstrategy.challenge

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.masterofchessstrategy.data.GameSessionRepository
import com.masterofchessstrategy.data.LoadGameSessionResult
import com.masterofchessstrategy.data.StoredGameMode
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.GameType
import kotlinx.coroutines.launch

internal data class PreparedBlindChallenge(val difficulty: Difficulty)

internal data class ChineseChessBlindUiState(
    val difficulty: Difficulty = Difficulty.EASY,
    val unlockedDifficulties: Set<Difficulty> = emptySet(),
    val isLoadingSavedGame: Boolean = true,
    val savedChallenge: PreparedBlindChallenge? = null,
)

/** Selects a blind-memory opponent and exposes only a compatible saved game. */
internal class ChineseChessBlindViewModel(
    private val sessionRepository: GameSessionRepository,
    unlockedDifficulties: Set<Difficulty>,
) : ViewModel() {
    var uiState by mutableStateOf(
        ChineseChessBlindUiState(
            difficulty = unlockedDifficulties.firstOrNull() ?: Difficulty.EASY,
            unlockedDifficulties = unlockedDifficulties.toSet(),
        ),
    )
        private set

    init {
        loadSavedChallenge()
    }

    fun selectDifficulty(difficulty: Difficulty) {
        if (difficulty !in uiState.unlockedDifficulties) return
        uiState = uiState.copy(difficulty = difficulty)
    }

    fun prepareNewChallenge(): PreparedBlindChallenge? =
        uiState.difficulty
            .takeIf { it in uiState.unlockedDifficulties }
            ?.let(::PreparedBlindChallenge)

    fun continueSavedChallenge(): PreparedBlindChallenge? = uiState.savedChallenge

    private fun loadSavedChallenge() {
        viewModelScope.launch {
            val snapshot = try {
                (sessionRepository.load(GameType.CHINESE_CHESS) as? LoadGameSessionResult.Loaded)
                    ?.snapshot
            } catch (_: RuntimeException) {
                null
            }
            val saved = snapshot?.takeIf {
                it.mode == StoredGameMode.BLIND_CHALLENGE &&
                    it.difficulty != null &&
                    it.difficulty in uiState.unlockedDifficulties &&
                    it.sessionVariantId.isEmpty()
            }?.let { PreparedBlindChallenge(requireNotNull(it.difficulty)) }
            uiState = uiState.copy(
                isLoadingSavedGame = false,
                savedChallenge = saved,
            )
        }
    }

    companion object {
        fun factory(
            repository: GameSessionRepository,
            unlockedDifficulties: Set<Difficulty>,
        ): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    ChineseChessBlindViewModel(repository, unlockedDifficulties)
                }
            }
    }
}
