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

internal data class PreparedTimedChallenge(
    val difficulty: Difficulty,
    val secondsPerMove: Int,
)

internal data class ChineseChessTimedChallengeUiState(
    val difficulty: Difficulty = Difficulty.EASY,
    val secondsPerMove: Int = TimedChallengeConfig.DEFAULT_SECONDS,
    val unlockedDifficulties: Set<Difficulty> = emptySet(),
    val isLoadingSavedGame: Boolean = true,
    val savedChallenge: PreparedTimedChallenge? = null,
)

/** Selects a per-move clock and exposes only a compatible saved challenge. */
internal class ChineseChessTimedChallengeViewModel(
    private val sessionRepository: GameSessionRepository,
    unlockedDifficulties: Set<Difficulty>,
) : ViewModel() {
    var uiState by mutableStateOf(
        ChineseChessTimedChallengeUiState(
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

    fun selectSecondsPerMove(seconds: Int) {
        if (seconds !in TimedChallengeConfig.ALLOWED_SECONDS) return
        uiState = uiState.copy(secondsPerMove = seconds)
    }

    fun prepareNewChallenge(): PreparedTimedChallenge? =
        uiState.difficulty
            .takeIf { it in uiState.unlockedDifficulties }
            ?.let { PreparedTimedChallenge(it, uiState.secondsPerMove) }

    fun continueSavedChallenge(): PreparedTimedChallenge? = uiState.savedChallenge

    private fun loadSavedChallenge() {
        viewModelScope.launch {
            val snapshot = try {
                (sessionRepository.load(GameType.CHINESE_CHESS) as? LoadGameSessionResult.Loaded)
                    ?.snapshot
            } catch (_: RuntimeException) {
                null
            }
            val saved = if (
                snapshot?.mode == StoredGameMode.TIMED_CHALLENGE &&
                snapshot.difficulty != null &&
                snapshot.difficulty in uiState.unlockedDifficulties &&
                snapshot.timeControlMinutes == TimedChallengeConfig.BACKING_CLOCK_MINUTES
            ) {
                try {
                    PreparedTimedChallenge(
                        difficulty = snapshot.difficulty,
                        secondsPerMove = TimedChallengeConfig.decodeSessionVariant(
                            snapshot.sessionVariantId,
                        ),
                    )
                } catch (_: IllegalArgumentException) {
                    null
                }
            } else {
                null
            }
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
                    ChineseChessTimedChallengeViewModel(
                        sessionRepository = repository,
                        unlockedDifficulties = unlockedDifficulties,
                    )
                }
            }
    }
}
