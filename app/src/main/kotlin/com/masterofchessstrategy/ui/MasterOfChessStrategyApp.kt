package com.masterofchessstrategy.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.BackHandler
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.masterofchessstrategy.R
import com.masterofchessstrategy.data.MocsDatabase
import com.masterofchessstrategy.data.AppSettings
import com.masterofchessstrategy.data.RoomAppSettingsRepository
import com.masterofchessstrategy.data.RoomGameSessionRepository
import com.masterofchessstrategy.data.RoomLastSelectionRepository
import com.masterofchessstrategy.data.RoomMatchStatisticsRepository
import com.masterofchessstrategy.data.RoomTutorialProgressRepository
import com.masterofchessstrategy.data.StoredGameMode
import com.masterofchessstrategy.engine.BoardPosition
import com.masterofchessstrategy.engine.ChineseChessPiece
import com.masterofchessstrategy.engine.ChineseChessPieceType
import com.masterofchessstrategy.engine.ChineseChessSide
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.game.ChineseChessFeedback
import com.masterofchessstrategy.game.ChineseChessGameUiState
import com.masterofchessstrategy.game.ChineseChessGameViewModel
import com.masterofchessstrategy.navigation.AppDestination
import com.masterofchessstrategy.navigation.AppNavigationViewModel
import com.masterofchessstrategy.navigation.HomeGameEntry
import com.masterofchessstrategy.navigation.QuickStartDestination
import com.masterofchessstrategy.progress.PlayerStatisticsViewModel
import com.masterofchessstrategy.settings.AppSettingsViewModel
import com.masterofchessstrategy.tutorial.ChineseChessTutorialViewModel
import com.masterofchessstrategy.ui.theme.MocsTheme

internal const val UNDO_BUTTON_TAG = "undo_button"
internal const val RESTART_BUTTON_TAG = "restart_button"
internal const val AI_BUTTON_TAG = "ai_button"
internal const val GAME_BACK_BUTTON_TAG = "game_back_button"
internal const val GAME_SETTINGS_BUTTON_TAG = "game_settings_button"

@Composable
fun MasterOfChessStrategyApp() {
    MocsTheme {
        val navController = rememberNavController()
        val context = LocalContext.current
        val database = remember { MocsDatabase.getInstance(context) }
        val gameSessionRepository = remember {
            RoomGameSessionRepository(database.activeGameDao())
        }
        val selectionRepository = remember {
            RoomLastSelectionRepository(database.lastSelectionDao())
        }
        val settingsRepository = remember {
            RoomAppSettingsRepository(database.appSettingsDao())
        }
        val tutorialRepository = remember {
            RoomTutorialProgressRepository(database.tutorialProgressDao())
        }
        val statisticsRepository = remember {
            RoomMatchStatisticsRepository(database.matchOutcomeDao())
        }
        val navigationFactory = remember(selectionRepository) {
            AppNavigationViewModel.factory(selectionRepository)
        }
        val navigationViewModel: AppNavigationViewModel = viewModel(
            factory = navigationFactory,
        )
        val settingsFactory = remember(settingsRepository) {
            AppSettingsViewModel.factory(settingsRepository)
        }
        val settingsViewModel: AppSettingsViewModel = viewModel(factory = settingsFactory)
        val tutorialFactory = remember(tutorialRepository) {
            ChineseChessTutorialViewModel.factory(tutorialRepository)
        }
        val tutorialViewModel: ChineseChessTutorialViewModel = viewModel(
            factory = tutorialFactory,
        )
        val statisticsFactory = remember(statisticsRepository) {
            PlayerStatisticsViewModel.factory(statisticsRepository)
        }
        val statisticsViewModel: PlayerStatisticsViewModel = viewModel(
            factory = statisticsFactory,
        )
        val quickStartEntries = if (
            navigationViewModel.chineseChessQuickStartDestination() == null
        ) {
            emptySet()
        } else {
            setOf(HomeGameEntry.CHINESE_CHESS)
        }
        NavHost(
            navController = navController,
            startDestination = AppDestination.HOME,
        ) {
            composable(AppDestination.HOME) {
                HomeScreen(
                    onGameSelected = { entry ->
                        if (entry == HomeGameEntry.CHINESE_CHESS) {
                            navController.navigate(AppDestination.CHINESE_CHESS_MODES)
                        }
                    },
                    quickStartEntries = quickStartEntries,
                    onQuickStart = { entry ->
                        if (entry == HomeGameEntry.CHINESE_CHESS) {
                            val destination = when (
                                navigationViewModel.chineseChessQuickStartDestination()
                            ) {
                                QuickStartDestination.GAME -> AppDestination.CHINESE_CHESS_GAME
                                QuickStartDestination.DIFFICULTY -> {
                                    AppDestination.CHINESE_CHESS_DIFFICULTY
                                }

                                QuickStartDestination.TUTORIAL -> {
                                    AppDestination.CHINESE_CHESS_TUTORIAL
                                }

                                QuickStartDestination.AI_GAME -> {
                                    if (tutorialViewModel.uiState.progress.isCompleted) {
                                        AppDestination.CHINESE_CHESS_AI_GAME
                                    } else {
                                        AppDestination.CHINESE_CHESS_DIFFICULTY
                                    }
                                }

                                QuickStartDestination.MODE_SELECTION -> {
                                    AppDestination.CHINESE_CHESS_MODES
                                }

                                null -> return@HomeScreen
                            }
                            navController.navigate(destination)
                        }
                    },
                    onSettings = {
                        navController.navigate(AppDestination.SETTINGS) {
                            launchSingleTop = true
                        }
                    },
                    playerSummary = LocalPlayerSummary(
                        rank = "未定级",
                        wins = statisticsViewModel.uiState.statistics.totalWins,
                        stars = statisticsViewModel.uiState.statistics.stars,
                        score = statisticsViewModel.uiState.statistics.score,
                    ),
                )
            }
            composable(AppDestination.CHINESE_CHESS_MODES) {
                ChineseChessModeScreen(
                    onBack = navController::popBackStack,
                    onLocalGame = {
                        navigationViewModel.recordChineseChessSelection(
                            StoredGameMode.LOCAL_TWO_PLAYER,
                        )
                        navController.navigate(AppDestination.CHINESE_CHESS_GAME)
                    },
                    onAiDifficulty = {
                        navigationViewModel.recordChineseChessSelection(
                            StoredGameMode.HUMAN_VS_AI,
                        )
                        navController.navigate(AppDestination.CHINESE_CHESS_DIFFICULTY)
                    },
                    onTutorial = {
                        navigationViewModel.recordChineseChessSelection(
                            StoredGameMode.TUTORIAL,
                        )
                        navController.navigate(AppDestination.CHINESE_CHESS_TUTORIAL)
                    },
                )
            }
            composable(AppDestination.CHINESE_CHESS_DIFFICULTY) {
                ChineseChessDifficultyScreen(
                    onBack = navController::popBackStack,
                    tutorialCompleted = tutorialViewModel.uiState.progress.isCompleted,
                    winsByDifficulty =
                        statisticsViewModel.uiState.statistics
                            .winsByGameAndDifficulty[GameType.CHINESE_CHESS]
                            .orEmpty(),
                    onDifficultySelected = { difficulty ->
                        if (
                            difficulty == Difficulty.EASY &&
                            tutorialViewModel.uiState.progress.isCompleted
                        ) {
                            navigationViewModel.recordChineseChessSelection(
                                StoredGameMode.HUMAN_VS_AI,
                                difficulty,
                            )
                            navController.navigate(AppDestination.CHINESE_CHESS_AI_GAME)
                        }
                    },
                )
            }
            composable(AppDestination.CHINESE_CHESS_TUTORIAL) {
                ChineseChessTutorialScreen(
                    state = tutorialViewModel.uiState,
                    onBack = navController::popBackStack,
                    onContinueReading = tutorialViewModel::advanceReadingStep,
                    onPracticeSquareTap = tutorialViewModel::onPracticeSquareTap,
                    onCompletePractice = tutorialViewModel::completePractice,
                    onAnswerQuiz = tutorialViewModel::answerQuiz,
                    onCompleteQuiz = tutorialViewModel::completeQuiz,
                )
            }
            composable(AppDestination.CHINESE_CHESS_GAME) {
                val factory = remember(gameSessionRepository) {
                    ChineseChessGameViewModel.factory(gameSessionRepository)
                }
                val gameViewModel: ChineseChessGameViewModel = viewModel(factory = factory)
                ChineseChessGameScreen(
                    state = gameViewModel.uiState,
                    onSquareTap = gameViewModel::onSquareTap,
                    onUndo = gameViewModel::undo,
                    onRestart = gameViewModel::restart,
                    onBack = navController::popBackStack,
                    settings = settingsViewModel.uiState.settings,
                    onSettings = {
                        navController.navigate(AppDestination.SETTINGS) {
                            launchSingleTop = true
                        }
                    },
                )
            }
            composable(AppDestination.CHINESE_CHESS_AI_GAME) {
                val factory = remember(gameSessionRepository, statisticsViewModel) {
                    ChineseChessGameViewModel.factory(
                        repository = gameSessionRepository,
                        mode = StoredGameMode.HUMAN_VS_AI,
                        difficulty = Difficulty.EASY,
                        onMatchFinished = statisticsViewModel::record,
                    )
                }
                val gameViewModel: ChineseChessGameViewModel = viewModel(factory = factory)
                ChineseChessGameScreen(
                    state = gameViewModel.uiState,
                    onSquareTap = gameViewModel::onSquareTap,
                    onUndo = gameViewModel::undo,
                    onRestart = gameViewModel::restart,
                    onBack = navController::popBackStack,
                    settings = settingsViewModel.uiState.settings,
                    onSettings = {
                        navController.navigate(AppDestination.SETTINGS) {
                            launchSingleTop = true
                        }
                    },
                )
            }
            composable(AppDestination.SETTINGS) {
                SettingsScreen(
                    state = settingsViewModel.uiState,
                    onBack = navController::popBackStack,
                    onDefaultDifficulty = settingsViewModel::setDefaultDifficulty,
                    onAutoContinue = settingsViewModel::setAutoContinue,
                    onSoundEnabled = settingsViewModel::setSoundEnabled,
                    onTimeLimitEnabled = settingsViewModel::setTimeLimitEnabled,
                    onAdjustDuration = settingsViewModel::adjustDuration,
                )
            }
        }
    }
}

@Composable
internal fun ChineseChessGameScreen(
    state: ChineseChessGameUiState,
    onSquareTap: (BoardPosition) -> Unit,
    onUndo: () -> Unit,
    onRestart: () -> Unit,
    onBack: () -> Unit,
    settings: AppSettings = AppSettings.DEFAULT,
    onSettings: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    BackHandler(enabled = state.isRestoring || state.isPersisting || state.isAiThinking) {
        // Keep the destination alive until the atomic Room operation finishes.
    }
    MocsTheme {
        Surface(modifier = modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                GameHeader(state, settings, onBack, onSettings)
                HorizontalDivider()
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    if (maxWidth >= 720.dp) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.spacedBy(18.dp),
                        ) {
                            BoardPanel(
                                state = state,
                                onSquareTap = onSquareTap,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                            )
                            GameControls(
                                state = state,
                                onUndo = onUndo,
                                onRestart = onRestart,
                                modifier = Modifier
                                    .widthIn(min = 240.dp, max = 320.dp)
                                    .fillMaxHeight(),
                            )
                        }
                    } else {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            BoardPanel(
                                state = state,
                                onSquareTap = onSquareTap,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                            )
                            GameControls(
                                state = state,
                                onUndo = onUndo,
                                onRestart = onRestart,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GameHeader(
    state: ChineseChessGameUiState,
    settings: AppSettings,
    onBack: () -> Unit,
    onSettings: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = onBack,
                enabled = !state.isRestoring && !state.isPersisting && !state.isAiThinking,
                modifier = Modifier.testTag(GAME_BACK_BUTTON_TAG),
            ) {
                Text(stringResource(R.string.back))
            }
            Column {
                Text(
                    text = stringResource(R.string.chinese_chess_title),
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(
                    text = stringResource(
                        if (state.isAiGame) {
                            R.string.easy_ai_game
                        } else {
                            R.string.local_two_player
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = gameStatusText(state),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = settings.gameDurationMinutes?.let {
                        stringResource(R.string.duration_minutes, it)
                    } ?: stringResource(R.string.unlimited_duration),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(
                onClick = onSettings,
                enabled = !state.isRestoring && !state.isPersisting && !state.isAiThinking,
                modifier = Modifier.testTag(GAME_SETTINGS_BUTTON_TAG),
            ) {
                Text(stringResource(R.string.settings_title))
            }
        }
    }
}

@Composable
private fun BoardPanel(
    state: ChineseChessGameUiState,
    onSquareTap: (BoardPosition) -> Unit,
    modifier: Modifier,
) {
    Card(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp),
            contentAlignment = Alignment.Center,
        ) {
            ChineseChessBoard(
                state = state,
                onSquareTap = onSquareTap,
            )
        }
    }
}

@Composable
private fun GameControls(
    state: ChineseChessGameUiState,
    onUndo: () -> Unit,
    onRestart: () -> Unit,
    modifier: Modifier,
) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.game_controls),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = selectionText(state),
                style = MaterialTheme.typography.bodyLarge,
            )

            state.feedback?.let { feedback ->
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Text(
                        text = feedbackText(feedback),
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = onUndo,
                    enabled = state.isInteractionEnabled && state.canUndo,
                    modifier = Modifier
                        .weight(1f)
                        .testTag(UNDO_BUTTON_TAG),
                ) {
                    Text(stringResource(R.string.undo))
                }
                OutlinedButton(
                    onClick = onRestart,
                    enabled = state.isInteractionEnabled,
                    modifier = Modifier
                        .weight(1f)
                        .testTag(RESTART_BUTTON_TAG),
                ) {
                    Text(stringResource(R.string.restart))
                }
            }

            HorizontalDivider()
            Text(
                text = stringResource(R.string.later_features),
                style = MaterialTheme.typography.titleMedium,
            )
            Button(
                onClick = {},
                enabled = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(AI_BUTTON_TAG),
            ) {
                Text(
                    stringResource(
                        if (state.isAiGame) {
                            R.string.easy_ai_enabled
                        } else {
                            R.string.ai_not_available
                        },
                    ),
                )
            }
            OutlinedButton(
                onClick = {},
                enabled = false,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.hint_not_available))
            }
            Text(
                text = stringResource(R.string.rules_scope_note),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun gameStatusText(state: ChineseChessGameUiState): String =
    when {
        !state.isEngineAvailable -> stringResource(R.string.engine_unavailable)
        state.isRestoring -> stringResource(R.string.restoring_game)
        state.isAiThinking -> stringResource(R.string.ai_thinking)
        state.isPersisting -> stringResource(R.string.saving_game)
        state.result == GameResult.FIRST_PLAYER_WIN -> stringResource(R.string.red_wins)
        state.result == GameResult.SECOND_PLAYER_WIN -> stringResource(R.string.black_wins)
        state.result == GameResult.DRAW -> stringResource(R.string.draw)
        state.currentSide == ChineseChessSide.RED -> stringResource(R.string.red_to_move)
        else -> stringResource(R.string.black_to_move)
    }

@Composable
private fun selectionText(state: ChineseChessGameUiState): String =
    if (state.isAiThinking) {
        stringResource(R.string.wait_for_ai)
    } else {
        state.selectedPosition?.let {
        stringResource(
            R.string.selected_position,
            it.x,
            it.y,
            state.legalDestinations.size,
        )
        } ?: stringResource(R.string.select_piece_instruction)
    }

@Composable
private fun feedbackText(feedback: ChineseChessFeedback): String =
    stringResource(
        when (feedback) {
            ChineseChessFeedback.SELECT_OWN_PIECE -> R.string.feedback_select_own
            ChineseChessFeedback.WRONG_SIDE -> R.string.feedback_wrong_side
            ChineseChessFeedback.ILLEGAL_MOVE -> R.string.feedback_illegal_move
            ChineseChessFeedback.MOVE_REJECTED -> R.string.feedback_move_rejected
            ChineseChessFeedback.NOTHING_TO_UNDO -> R.string.feedback_nothing_to_undo
            ChineseChessFeedback.MOVE_UNDONE -> R.string.feedback_move_undone
            ChineseChessFeedback.GAME_RESTARTED -> R.string.feedback_game_restarted
            ChineseChessFeedback.GAME_FINISHED -> R.string.feedback_game_finished
            ChineseChessFeedback.ENGINE_UNAVAILABLE -> R.string.engine_unavailable
            ChineseChessFeedback.GAME_RESTORED -> R.string.feedback_game_restored
            ChineseChessFeedback.RESTORE_REJECTED -> R.string.feedback_restore_rejected
            ChineseChessFeedback.SAVE_FAILED -> R.string.feedback_save_failed
            ChineseChessFeedback.AI_MOVED -> R.string.feedback_ai_moved
            ChineseChessFeedback.AI_MOVE_FAILED -> R.string.feedback_ai_move_failed
        },
    )

@Preview(
    widthDp = 960,
    heightDp = 540,
    showBackground = true,
)
@Composable
private fun ChineseChessGameScreenPreview() {
    ChineseChessGameScreen(
        state = previewGameState(),
        onSquareTap = {},
        onUndo = {},
        onRestart = {},
        onBack = {},
    )
}

private fun previewGameState(): ChineseChessGameUiState {
    val board = MutableList<ChineseChessPiece?>(90) { null }
    board[0] = ChineseChessPiece(ChineseChessPieceType.CHARIOT, ChineseChessSide.BLACK)
    board[4] = ChineseChessPiece(ChineseChessPieceType.GENERAL, ChineseChessSide.BLACK)
    board[85] = ChineseChessPiece(ChineseChessPieceType.GENERAL, ChineseChessSide.RED)
    board[89] = ChineseChessPiece(ChineseChessPieceType.CHARIOT, ChineseChessSide.RED)
    return ChineseChessGameUiState(board = board)
}
