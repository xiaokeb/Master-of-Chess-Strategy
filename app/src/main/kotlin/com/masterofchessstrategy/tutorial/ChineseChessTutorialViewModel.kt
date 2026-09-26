package com.masterofchessstrategy.tutorial

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.masterofchessstrategy.data.LoadTutorialProgressResult
import com.masterofchessstrategy.data.TutorialProgress
import com.masterofchessstrategy.data.TutorialProgressRepository
import com.masterofchessstrategy.engine.BoardPosition
import com.masterofchessstrategy.engine.GameType
import kotlinx.coroutines.launch

internal enum class TutorialStage {
    RULES,
    PIECES,
    PRACTICE,
    QUIZ,
    COMPLETE,
}

internal enum class TutorialFeedback {
    SELECT_SOLDIER,
    TRY_FORWARD,
    PRACTICE_SOLVED,
    QUIZ_CORRECT,
    QUIZ_INCORRECT,
    LOAD_RECOVERED,
    SAVE_FAILED,
}

internal enum class StalemateAnswer {
    SIDE_TO_MOVE_LOSES,
    DRAW,
    SKIP_TURN,
}

internal data class ChineseChessTutorialUiState(
    val progress: TutorialProgress = TutorialProgress.empty(GameType.CHINESE_CHESS),
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val selectedPracticePosition: BoardPosition? = null,
    val isPracticeSolved: Boolean = false,
    val selectedAnswer: StalemateAnswer? = null,
    val isQuizCorrect: Boolean = false,
    val feedback: TutorialFeedback? = null,
) {
    val stage: TutorialStage
        get() = TutorialStage.entries[progress.completedStepCount]

    val isInteractionEnabled: Boolean
        get() = !isLoading && !isSaving
}

/**
 * Owns the versioned tutorial flow and commits each completed checkpoint.
 *
 * The practice deliberately validates one taught move instead of duplicating
 * the full native rules engine inside the UI layer.
 */
internal class ChineseChessTutorialViewModel(
    private val repository: TutorialProgressRepository,
    private val nowEpochMillis: () -> Long = { System.currentTimeMillis() },
) : ViewModel() {
    var uiState by mutableStateOf(ChineseChessTutorialUiState())
        private set

    init {
        loadProgress()
    }

    fun advanceReadingStep() {
        if (
            !uiState.isInteractionEnabled ||
            uiState.stage !in setOf(TutorialStage.RULES, TutorialStage.PIECES)
        ) {
            return
        }
        persistCompletedStep(uiState.progress.completedStepCount + 1)
    }

    fun onPracticeSquareTap(position: BoardPosition) {
        if (!uiState.isInteractionEnabled || uiState.stage != TutorialStage.PRACTICE) return
        if (uiState.isPracticeSolved) return

        uiState = when {
            position == PRACTICE_SOLDIER_START -> uiState.copy(
                selectedPracticePosition = position,
                feedback = TutorialFeedback.TRY_FORWARD,
            )

            uiState.selectedPracticePosition == PRACTICE_SOLDIER_START &&
                position == PRACTICE_SOLDIER_TARGET -> uiState.copy(
                selectedPracticePosition = null,
                isPracticeSolved = true,
                feedback = TutorialFeedback.PRACTICE_SOLVED,
            )

            else -> uiState.copy(
                selectedPracticePosition = null,
                feedback = TutorialFeedback.SELECT_SOLDIER,
            )
        }
    }

    fun completePractice() {
        if (
            uiState.isInteractionEnabled &&
            uiState.stage == TutorialStage.PRACTICE &&
            uiState.isPracticeSolved
        ) {
            persistCompletedStep(3)
        }
    }

    fun answerQuiz(answer: StalemateAnswer) {
        if (!uiState.isInteractionEnabled || uiState.stage != TutorialStage.QUIZ) return
        val isCorrect = answer == StalemateAnswer.SIDE_TO_MOVE_LOSES
        uiState = uiState.copy(
            selectedAnswer = answer,
            isQuizCorrect = isCorrect,
            feedback = if (isCorrect) {
                TutorialFeedback.QUIZ_CORRECT
            } else {
                TutorialFeedback.QUIZ_INCORRECT
            },
        )
    }

    fun completeQuiz() {
        if (
            uiState.isInteractionEnabled &&
            uiState.stage == TutorialStage.QUIZ &&
            uiState.isQuizCorrect
        ) {
            persistCompletedStep(TutorialProgress.TOTAL_STEP_COUNT)
        }
    }

    private fun persistCompletedStep(completedStepCount: Int) {
        val previous = uiState.progress
        val updated = TutorialProgress(
            gameType = GameType.CHINESE_CHESS,
            contentVersion = TutorialProgress.CURRENT_CONTENT_VERSION,
            completedStepCount = completedStepCount,
            isCompleted = completedStepCount == TutorialProgress.TOTAL_STEP_COUNT,
            updatedAtEpochMillis = nowEpochMillis(),
        )
        uiState = uiState.copy(
            progress = updated,
            isSaving = true,
            feedback = null,
        )
        viewModelScope.launch {
            try {
                repository.save(updated)
                uiState = uiState.copy(isSaving = false)
            } catch (_: RuntimeException) {
                uiState = uiState.copy(
                    progress = previous,
                    isSaving = false,
                    feedback = TutorialFeedback.SAVE_FAILED,
                )
            }
        }
    }

    internal fun loadProgress() {
        uiState = ChineseChessTutorialUiState(isLoading = true)
        viewModelScope.launch {
            val result = try {
                repository.load(GameType.CHINESE_CHESS)
            } catch (_: RuntimeException) {
                uiState = ChineseChessTutorialUiState(
                    isLoading = false,
                    feedback = TutorialFeedback.LOAD_RECOVERED,
                )
                return@launch
            }
            when (result) {
                LoadTutorialProgressResult.NotFound -> {
                    uiState = ChineseChessTutorialUiState(isLoading = false)
                }

                LoadTutorialProgressResult.Incompatible -> {
                    clearIncompatibleProgress()
                    uiState = ChineseChessTutorialUiState(
                        isLoading = false,
                        feedback = TutorialFeedback.LOAD_RECOVERED,
                    )
                }

                is LoadTutorialProgressResult.Loaded -> {
                    uiState = ChineseChessTutorialUiState(
                        progress = result.progress,
                        isLoading = false,
                    )
                }
            }
        }
    }

    private suspend fun clearIncompatibleProgress() {
        try {
            repository.clear(GameType.CHINESE_CHESS)
        } catch (_: RuntimeException) {
            // Invalid stored progress remains hidden behind a safe empty state.
        }
    }

    companion object {
        val PRACTICE_SOLDIER_START = BoardPosition(0, 6)
        val PRACTICE_SOLDIER_TARGET = BoardPosition(0, 5)

        fun factory(repository: TutorialProgressRepository): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    ChineseChessTutorialViewModel(repository)
                }
            }
    }
}
