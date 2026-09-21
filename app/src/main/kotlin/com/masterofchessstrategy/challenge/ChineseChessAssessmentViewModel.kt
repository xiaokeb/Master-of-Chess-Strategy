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

internal data class PreparedAssessmentChallenge(
    val difficulty: Difficulty,
    val state: AssessmentChallengeState,
)

internal data class ChineseChessAssessmentUiState(
    val isLoadingSavedGame: Boolean = true,
    val savedChallenge: PreparedAssessmentChallenge? = null,
)

/** Starts a five-game placement series or exposes its canonical saved state. */
internal class ChineseChessAssessmentViewModel(
    private val sessionRepository: GameSessionRepository,
) : ViewModel() {
    var uiState by mutableStateOf(ChineseChessAssessmentUiState())
        private set

    init {
        loadSavedChallenge()
    }

    fun prepareNewChallenge() = PreparedAssessmentChallenge(
        difficulty = Difficulty.MEDIUM,
        state = AssessmentChallengeState(),
    )

    fun continueSavedChallenge(): PreparedAssessmentChallenge? = uiState.savedChallenge

    private fun loadSavedChallenge() {
        viewModelScope.launch {
            val snapshot = try {
                (sessionRepository.load(GameType.CHINESE_CHESS) as? LoadGameSessionResult.Loaded)
                    ?.snapshot
            } catch (_: RuntimeException) {
                null
            }
            val saved = if (
                snapshot?.mode == StoredGameMode.ASSESSMENT_CHALLENGE &&
                snapshot.difficulty != null
            ) {
                try {
                    PreparedAssessmentChallenge(
                        difficulty = snapshot.difficulty,
                        state = AssessmentChallengeStateCodec.decode(snapshot.sessionVariantId),
                    )
                } catch (_: IllegalArgumentException) {
                    null
                }
            } else {
                null
            }
            uiState = ChineseChessAssessmentUiState(
                isLoadingSavedGame = false,
                savedChallenge = saved,
            )
        }
    }

    companion object {
        fun factory(repository: GameSessionRepository): ViewModelProvider.Factory =
            viewModelFactory {
                initializer { ChineseChessAssessmentViewModel(repository) }
            }
    }
}
