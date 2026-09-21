package com.masterofchessstrategy.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.masterofchessstrategy.game.ChineseChessGameUiState
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.ChineseChessSide
import com.masterofchessstrategy.opening.ChineseChessOpeningFrame
import com.masterofchessstrategy.opening.ChineseChessOpeningUiState
import org.junit.Rule
import org.junit.Test

class ChineseChessGameScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun foundationControlsExposeCurrentCapabilityBoundary() {
        composeRule.setContent {
            ChineseChessGameScreen(
                state = ChineseChessGameUiState(),
                onSquareTap = {},
                onUndo = {},
                onHint = {},
                onResign = {},
                onRestart = {},
                onBack = {},
            )
        }

        composeRule.onNodeWithTag(CHINESE_CHESS_BOARD_TAG).assertExists()
        composeRule.onNodeWithTag(UNDO_BUTTON_TAG).assertIsNotEnabled()
        composeRule.onNodeWithTag(HINT_BUTTON_TAG).assertIsNotEnabled()
        composeRule.onNodeWithTag(RESIGN_BUTTON_TAG).assertIsEnabled()
        composeRule.onNodeWithTag(RESTART_BUTTON_TAG).assertIsEnabled()
        composeRule.onNodeWithTag(DRAW_BUTTON_TAG).assertIsNotEnabled()
    }

    @Test
    fun mediumAssistanceControlsExposeRemainingCapability() {
        composeRule.setContent {
            ChineseChessGameScreen(
                state = ChineseChessGameUiState(
                    canUndo = true,
                    undoRemaining = 2,
                    canRequestHint = true,
                    hintRemaining = 3,
                    isAiGame = true,
                ),
                onSquareTap = {},
                onUndo = {},
                onHint = {},
                onResign = {},
                onRestart = {},
                onBack = {},
            )
        }

        composeRule.onNodeWithTag(UNDO_BUTTON_TAG).assertIsEnabled()
        composeRule.onNodeWithTag(HINT_BUTTON_TAG).assertIsEnabled()
        composeRule.onNodeWithTag(RESIGN_BUTTON_TAG).assertIsEnabled()
    }

    @Test
    fun autoPlayShowsPauseAndSpeedInsteadOfHumanActions() {
        composeRule.setContent {
            ChineseChessGameScreen(
                state = ChineseChessGameUiState(
                    isAiGame = true,
                    isAutoPlay = true,
                    isAiThinking = true,
                ),
                onSquareTap = {},
                onUndo = {},
                onHint = {},
                onResign = {},
                onRestart = {},
                onBack = {},
            )
        }

        composeRule.onNodeWithTag(AUTO_PLAY_TOGGLE_TAG).assertIsEnabled()
        composeRule.onNodeWithTag(AUTO_PLAY_SPEED_TAG).assertIsEnabled()
        composeRule.onNodeWithTag(RESIGN_BUTTON_TAG).assertDoesNotExist()
    }

    @Test
    fun completedStreakGameOffersDedicatedNextGameAction() {
        composeRule.setContent {
            ChineseChessGameScreen(
                state = ChineseChessGameUiState(
                    result = GameResult.FIRST_PLAYER_WIN,
                    isAiGame = true,
                    difficulty = Difficulty.EASY,
                    isStreakChallenge = true,
                    currentStreak = 3,
                    bestStreak = 3,
                    streakNextDifficulty = Difficulty.MEDIUM,
                ),
                onSquareTap = {},
                onUndo = {},
                onHint = {},
                onResign = {},
                onRestart = {},
                onContinueStreak = {},
                onBack = {},
            )
        }

        composeRule.onNodeWithTag(STREAK_NEXT_GAME_TAG).assertIsEnabled()
        composeRule.onNodeWithTag(RESTART_BUTTON_TAG).assertDoesNotExist()
    }

    @Test
    fun blindChallengeKeepsBoardButDisablesMemoryAids() {
        composeRule.setContent {
            ChineseChessGameScreen(
                state = ChineseChessGameUiState(
                    board = List(90) { null },
                    isAiGame = true,
                    difficulty = Difficulty.EASY,
                    isBlindChess = true,
                    canUndo = false,
                    canRequestHint = false,
                ),
                onSquareTap = {},
                onUndo = {},
                onHint = {},
                onResign = {},
                onRestart = {},
                onBack = {},
            )
        }

        composeRule.onNodeWithTag(CHINESE_CHESS_BOARD_TAG).assertExists()
        composeRule.onNodeWithTag(UNDO_BUTTON_TAG).assertIsNotEnabled()
        composeRule.onNodeWithTag(HINT_BUTTON_TAG).assertIsNotEnabled()
    }

    @Test
    fun completedAssessmentGameOffersNextAdaptiveOpponent() {
        composeRule.setContent {
            ChineseChessGameScreen(
                state = ChineseChessGameUiState(
                    result = GameResult.FIRST_PLAYER_WIN,
                    isAiGame = true,
                    difficulty = Difficulty.MEDIUM,
                    isAssessmentChallenge = true,
                    assessmentCompletedGames = 1,
                    assessmentRating = 1_400,
                    assessmentWins = 1,
                    assessmentNextDifficulty = Difficulty.HARD,
                ),
                onSquareTap = {},
                onUndo = {},
                onHint = {},
                onResign = {},
                onRestart = {},
                onContinueAssessment = {},
                onBack = {},
            )
        }

        composeRule.onNodeWithTag(ASSESSMENT_NEXT_GAME_TAG).assertIsEnabled()
        composeRule.onNodeWithTag(RESTART_BUTTON_TAG).assertDoesNotExist()
    }

    @Test
    fun openingTrainingExposesValidatedAutoPlayAction() {
        composeRule.setContent {
            ChineseChessOpeningScreen(
                state = ChineseChessOpeningUiState(
                    frames = listOf(
                        ChineseChessOpeningFrame(
                            board = List(90) { null },
                            sideToMove = ChineseChessSide.RED,
                            stepTitle = "标准初始局面",
                            explanation = "观察阵形。",
                        ),
                    ),
                    isLoading = false,
                    difficulty = Difficulty.EASY,
                    unlockedDifficulties = setOf(Difficulty.EASY),
                    endpointState = byteArrayOf(1),
                ),
                onBack = {},
                onLineSelected = {},
                onPrevious = {},
                onNext = {},
                onTogglePlayback = {},
                onSpeedChange = {},
                onDifficultySelected = {},
                onStartAutoPlay = {},
            )
        }

        composeRule.onNodeWithTag(OPENING_TRAINING_SCREEN_TAG).assertExists()
        composeRule.onNodeWithTag(OPENING_AUTO_PLAY_TAG).assertIsEnabled()
    }
}
