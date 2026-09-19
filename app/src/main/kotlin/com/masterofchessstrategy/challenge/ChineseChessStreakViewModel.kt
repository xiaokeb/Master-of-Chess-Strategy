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

internal data class PreparedStreakChallenge(
    val difficulty: Difficulty,
    val state: StreakChallengeState,
)

internal data class ChineseChessStreakUiState(
    val startingDifficulty: Difficulty = Difficulty.EASY,
    val unlockedDifficulties: Set<Difficulty> = emptySet(),
    val isLoadingSavedGame: Boolean = true,
    val savedChallenge: PreparedStreakChallenge? = null,
)

/** Creates a new streak series or exposes a canonical active series. */
internal class ChineseChessStreakViewModel(
    private val sessionRepository: GameSessionRepository,
    unlockedDifficulties: Set<Difficulty>,
) : ViewModel() {
    var uiState by mutableStateOf(
        ChineseChessStreakUiState(
            startingDifficulty = unlockedDifficulties.firstOrNull() ?: Difficulty.EASY,
            unlockedDifficulties = unlockedDifficulties.toSet(),
        ),
    )
        private set

    init {
        loadSavedChallenge()
    }

    fun selectStartingDifficulty(difficulty: Difficulty) {
        if (difficulty !in uiState.unlockedDifficulties) return
        uiState = uiState.copy(startingDifficulty = difficulty)
    }

    fun prepareNewChallenge(): PreparedStreakChallenge? =
        uiState.startingDifficulty
            .takeIf { it in uiState.unlockedDifficulties }
            ?.let { PreparedStreakChallenge(it, StreakChallengeState()) }

    fun continueSavedChallenge(): PreparedStreakChallenge? = uiState.savedChallenge

    private fun loadSavedChallenge() {
        viewModelScope.launch {
            val snapshot = try {
                (sessionRepository.load(GameType.CHINESE_CHESS) as? LoadGameSessionResult.Loaded)
                    ?.snapshot
            } catch (_: RuntimeException) {
                null
            }
            val saved = if (
                snapshot?.mode == StoredGameMode.STREAK_CHALLENGE &&
                snapshot.difficulty != null
            ) {
                try {
                    PreparedStreakChallenge(
                        difficulty = snapshot.difficulty,
                        state = StreakChallengeStateCodec.decode(snapshot.sessionVariantId),
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
                    ChineseChessStreakViewModel(
                        sessionRepository = repository,
                        unlockedDifficulties = unlockedDifficulties,
                    )
                }
            }
    }
}
