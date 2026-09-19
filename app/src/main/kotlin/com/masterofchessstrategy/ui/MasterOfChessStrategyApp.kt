package com.masterofchessstrategy.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.masterofchessstrategy.R
import com.masterofchessstrategy.data.MocsDatabase
import com.masterofchessstrategy.data.RoomAppSettingsRepository
import com.masterofchessstrategy.data.RoomGameSessionRepository
import com.masterofchessstrategy.data.RoomGameRecordRepository
import com.masterofchessstrategy.data.RoomEndgameProgressRepository
import com.masterofchessstrategy.data.RoomLastSelectionRepository
import com.masterofchessstrategy.data.RoomLocalDataBackupRepository
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
import com.masterofchessstrategy.engine.NativeChineseChessEngine
import com.masterofchessstrategy.engine.RestoreResult
import com.masterofchessstrategy.endgame.ChineseChessEndgamePackParser
import com.masterofchessstrategy.endgame.ChineseChessEndgameViewModel
import com.masterofchessstrategy.game.ChineseChessFeedback
import com.masterofchessstrategy.game.ChineseChessGameUiState
import com.masterofchessstrategy.game.ChineseChessGameViewModel
import com.masterofchessstrategy.game.ChineseChessSoundPlayer
import com.masterofchessstrategy.game.PikafishNetworkProvider
import com.masterofchessstrategy.navigation.AppDestination
import com.masterofchessstrategy.navigation.AppNavigationViewModel
import com.masterofchessstrategy.navigation.HomeGameEntry
import com.masterofchessstrategy.navigation.QuickStartDestination
import com.masterofchessstrategy.navigation.chineseChessDifficulties
import com.masterofchessstrategy.progress.PlayerStatisticsViewModel
import com.masterofchessstrategy.records.ChineseChessReplayViewModel
import com.masterofchessstrategy.records.GameRecordsViewModel
import com.masterofchessstrategy.settings.AppSettingsViewModel
import com.masterofchessstrategy.settings.LocalDataBackupViewModel
import com.masterofchessstrategy.tutorial.ChineseChessTutorialViewModel
import com.masterofchessstrategy.ui.theme.MocsTheme

internal const val UNDO_BUTTON_TAG = "undo_button"
internal const val RESTART_BUTTON_TAG = "restart_button"
internal const val HINT_BUTTON_TAG = "hint_button"
internal const val RESIGN_BUTTON_TAG = "resign_button"
internal const val DRAW_BUTTON_TAG = "draw_button"
internal const val AUTO_PLAY_TOGGLE_TAG = "auto_play_toggle"
internal const val AUTO_PLAY_SPEED_TAG = "auto_play_speed"
internal const val GAME_BACK_BUTTON_TAG = "game_back_button"
internal const val GAME_SETTINGS_BUTTON_TAG = "game_settings_button"

@Composable
fun MasterOfChessStrategyApp() {
    MocsTheme {
        val navController = rememberNavController()
        val context = LocalContext.current
        val pikafishNetworkProvider = remember(context.applicationContext) {
            PikafishNetworkProvider(context.applicationContext)
        }
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
        val gameRecordRepository = remember {
            RoomGameRecordRepository(database.gameRecordDao())
        }
        val endgamePack = remember(context.applicationContext) {
            ChineseChessEndgamePackParser.loadBundled(context.assets)
        }
        val endgameRepository = remember(database, endgamePack) {
            RoomEndgameProgressRepository(database.endgameProgressDao(), endgamePack)
        }
        val backupRepository = remember(database, endgamePack) {
            RoomLocalDataBackupRepository(
                database = database,
                endgamePack = endgamePack,
                validateEngineState = ::isValidChineseChessState,
            )
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
        val backupFactory = remember(backupRepository) {
            LocalDataBackupViewModel.factory(backupRepository)
        }
        val backupViewModel: LocalDataBackupViewModel = viewModel(factory = backupFactory)
        var pendingRestoreUri by remember { mutableStateOf<android.net.Uri?>(null) }
        val createBackupDocument = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/octet-stream"),
        ) { uri ->
            if (uri != null) {
                backupViewModel.export {
                    context.contentResolver.openOutputStream(uri, "w")
                }
            }
        }
        val openBackupDocument = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri ->
            pendingRestoreUri = uri
        }
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
        val gameRecordsFactory = remember(gameRecordRepository) {
            GameRecordsViewModel.factory(gameRecordRepository)
        }
        val gameRecordsViewModel: GameRecordsViewModel = viewModel(
            factory = gameRecordsFactory,
        )
        val endgameFactory = remember(endgamePack, endgameRepository) {
            ChineseChessEndgameViewModel.factory(endgamePack, endgameRepository)
        }
        val endgameViewModel: ChineseChessEndgameViewModel = viewModel(
            factory = endgameFactory,
        )
        val quickStartEntries = if (
            navigationViewModel.chineseChessQuickStartDestination() == null
        ) {
            emptySet()
        } else {
            setOf(HomeGameEntry.CHINESE_CHESS)
        }
        val chineseChessWins = statisticsViewModel.uiState.statistics
            .winsByGameAndDifficulty[GameType.CHINESE_CHESS]
            .orEmpty()
        val difficultyEntries = chineseChessDifficulties(
            tutorialCompleted = tutorialViewModel.uiState.progress.isCompleted,
            winsByDifficulty = chineseChessWins,
        )
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
                                    val difficulty = navigationViewModel.uiState
                                        .lastChineseChessSelection
                                        ?.difficulty
                                    if (
                                        difficulty != null &&
                                        difficultyEntries.any {
                                            it.difficulty == difficulty && it.isPlayable
                                        }
                                    ) {
                                        AppDestination.chineseChessAiGame(difficulty)
                                    } else {
                                        AppDestination.CHINESE_CHESS_DIFFICULTY
                                    }
                                }

                                QuickStartDestination.AUTO_PLAY_GAME -> {
                                    val difficulty = navigationViewModel.uiState
                                        .lastChineseChessSelection
                                        ?.difficulty
                                    if (
                                        difficulty != null &&
                                        difficultyEntries.any {
                                            it.difficulty == difficulty && it.isPlayable
                                        }
                                    ) {
                                        AppDestination.chineseChessAutoPlayGame(difficulty)
                                    } else {
                                        AppDestination.CHINESE_CHESS_AUTO_PLAY_DIFFICULTY
                                    }
                                }

                                QuickStartDestination.ENDGAME_CATALOG -> {
                                    AppDestination.CHINESE_CHESS_ENDGAMES
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
                    onRecords = {
                        navController.navigate(AppDestination.GAME_RECORDS) {
                            launchSingleTop = true
                        }
                    },
                    playerSummary = LocalPlayerSummary(
                        rank = "未定级",
                        wins = statisticsViewModel.uiState.statistics.totalWins,
                        stars = statisticsViewModel.uiState.statistics.stars +
                            endgameViewModel.uiState.progress.totalStars,
                        score = statisticsViewModel.uiState.statistics.score +
                            endgameViewModel.uiState.progress.totalScore,
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
                    onAutoPlayDifficulty = {
                        navigationViewModel.recordChineseChessSelection(
                            StoredGameMode.AI_AUTO_PLAY,
                        )
                        navController.navigate(
                            AppDestination.CHINESE_CHESS_AUTO_PLAY_DIFFICULTY,
                        )
                    },
                    onTutorial = {
                        navigationViewModel.recordChineseChessSelection(
                            StoredGameMode.TUTORIAL,
                        )
                        navController.navigate(AppDestination.CHINESE_CHESS_TUTORIAL)
                    },
                    onEndgame = {
                        navigationViewModel.recordChineseChessSelection(
                            StoredGameMode.ENDGAME,
                        )
                        navController.navigate(AppDestination.CHINESE_CHESS_ENDGAMES)
                    },
                )
            }
            composable(AppDestination.CHINESE_CHESS_DIFFICULTY) {
                ChineseChessDifficultyScreen(
                    onBack = navController::popBackStack,
                    tutorialCompleted = tutorialViewModel.uiState.progress.isCompleted,
                    winsByDifficulty =
                        chineseChessWins,
                    onDifficultySelected = { difficulty ->
                        if (
                            difficultyEntries.any {
                                it.difficulty == difficulty && it.isPlayable
                            }
                        ) {
                            navigationViewModel.recordChineseChessSelection(
                                StoredGameMode.HUMAN_VS_AI,
                                difficulty,
                            )
                            navController.navigate(
                                AppDestination.chineseChessAiGame(difficulty),
                            )
                        }
                    },
                )
            }
            composable(AppDestination.CHINESE_CHESS_AUTO_PLAY_DIFFICULTY) {
                ChineseChessDifficultyScreen(
                    onBack = navController::popBackStack,
                    tutorialCompleted = tutorialViewModel.uiState.progress.isCompleted,
                    winsByDifficulty = chineseChessWins,
                    onDifficultySelected = { difficulty ->
                        if (
                            difficultyEntries.any {
                                it.difficulty == difficulty && it.isPlayable
                            }
                        ) {
                            navigationViewModel.recordChineseChessSelection(
                                StoredGameMode.AI_AUTO_PLAY,
                                difficulty,
                            )
                            navController.navigate(
                                AppDestination.chineseChessAutoPlayGame(difficulty),
                            )
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
            composable(AppDestination.CHINESE_CHESS_ENDGAMES) {
                ChineseChessEndgameScreen(
                    state = endgameViewModel.uiState,
                    onBack = navController::popBackStack,
                    onModeSelected = endgameViewModel::selectMode,
                    onDifficultySelected = endgameViewModel::selectDifficulty,
                    onThemeSelected = endgameViewModel::selectTheme,
                    onRandomChallenge = endgameViewModel::randomChallenge,
                    onLevelSelected = { levelId ->
                        val entry = endgameViewModel.uiState.entries.firstOrNull {
                            it.level.id == levelId
                        }
                        if (entry?.isUnlocked == true) {
                            navController.navigate(AppDestination.chineseChessEndgame(levelId))
                        }
                    },
                )
            }
            composable(
                route = AppDestination.CHINESE_CHESS_ENDGAME_GAME,
                arguments = listOf(
                    navArgument(AppDestination.ENDGAME_LEVEL_ARGUMENT) {
                        type = NavType.StringType
                    },
                ),
            ) { backStackEntry ->
                val levelId = checkNotNull(
                    backStackEntry.arguments?.getString(
                        AppDestination.ENDGAME_LEVEL_ARGUMENT,
                    ),
                )
                val level = checkNotNull(endgameViewModel.level(levelId)) {
                    "Unknown Chinese chess endgame route"
                }
                val factory = remember(
                    gameSessionRepository,
                    gameRecordsViewModel,
                    endgameViewModel,
                    level,
                    pikafishNetworkProvider,
                ) {
                    ChineseChessGameViewModel.factory(
                        repository = gameSessionRepository,
                        mode = StoredGameMode.ENDGAME,
                        difficulty = level.difficulty,
                        initialPositionState = level.initialEngineState,
                        sessionVariantId = level.id,
                        endgameTitle = level.title,
                        endgameMaxPlayerMoves = level.maxPlayerMoves,
                        onGameRecorded = { record ->
                            gameRecordsViewModel.record(record)
                            if (record.result == GameResult.FIRST_PLAYER_WIN) {
                                endgameViewModel.complete(
                                    level.id,
                                    (record.moveCount + 1) / 2,
                                )
                            }
                        },
                        engineFactory = {
                            NativeChineseChessEngine(
                                pikafishNetworkProvider::requireNetworkPath,
                            )
                        },
                    )
                }
                val gameViewModel: ChineseChessGameViewModel = viewModel(factory = factory)
                ChineseChessGameSoundEffect(
                    gameViewModel,
                    settingsViewModel.uiState.settings.soundEnabled,
                )
                ChineseChessGameScreen(
                    state = gameViewModel.uiState,
                    onSquareTap = gameViewModel::onSquareTap,
                    onUndo = gameViewModel::undo,
                    onHint = gameViewModel::requestHint,
                    onResign = gameViewModel::resign,
                    onRestart = gameViewModel::restart,
                    onBack = navController::popBackStack,
                    onSettings = {
                        navController.navigate(AppDestination.SETTINGS) {
                            launchSingleTop = true
                        }
                    },
                )
            }
            composable(AppDestination.CHINESE_CHESS_GAME) {
                val timeControlMinutes =
                    settingsViewModel.uiState.settings.gameDurationMinutes
                val factory = remember(
                    gameSessionRepository,
                    gameRecordsViewModel,
                    timeControlMinutes,
                ) {
                    ChineseChessGameViewModel.factory(
                        repository = gameSessionRepository,
                        timeControlMinutes = timeControlMinutes,
                        onGameRecorded = gameRecordsViewModel::record,
                    )
                }
                val gameViewModel: ChineseChessGameViewModel = viewModel(factory = factory)
                ChineseChessGameSoundEffect(
                    gameViewModel,
                    settingsViewModel.uiState.settings.soundEnabled,
                )
                ChineseChessGameScreen(
                    state = gameViewModel.uiState,
                    onSquareTap = gameViewModel::onSquareTap,
                    onUndo = gameViewModel::undo,
                    onHint = gameViewModel::requestHint,
                    onResign = gameViewModel::resign,
                    onDraw = gameViewModel::offerOrAcceptDraw,
                    onRestart = gameViewModel::restart,
                    onBack = navController::popBackStack,
                    onSettings = {
                        navController.navigate(AppDestination.SETTINGS) {
                            launchSingleTop = true
                        }
                    },
                )
            }
            composable(
                route = AppDestination.CHINESE_CHESS_AI_GAME,
                arguments = listOf(
                    navArgument(AppDestination.AI_DIFFICULTY_ARGUMENT) {
                        type = NavType.IntType
                    },
                ),
            ) { backStackEntry ->
                val difficultyCode = backStackEntry.arguments
                    ?.getInt(AppDestination.AI_DIFFICULTY_ARGUMENT)
                val difficulty = checkNotNull(
                    Difficulty.entries.firstOrNull {
                        it.code == difficultyCode
                    },
                ) {
                    "Unsupported Chinese chess AI route"
                }
                val factory = remember(
                    gameSessionRepository,
                    statisticsViewModel,
                    gameRecordsViewModel,
                    difficulty,
                    settingsViewModel.uiState.settings.gameDurationMinutes,
                    pikafishNetworkProvider,
                ) {
                    ChineseChessGameViewModel.factory(
                        repository = gameSessionRepository,
                        mode = StoredGameMode.HUMAN_VS_AI,
                        difficulty = difficulty,
                        onMatchFinished = statisticsViewModel::record,
                        onGameRecorded = gameRecordsViewModel::record,
                        timeControlMinutes =
                            settingsViewModel.uiState.settings.gameDurationMinutes,
                        engineFactory = {
                            NativeChineseChessEngine(
                                pikafishNetworkProvider::requireNetworkPath,
                            )
                        },
                    )
                }
                val gameViewModel: ChineseChessGameViewModel = viewModel(factory = factory)
                ChineseChessGameSoundEffect(
                    gameViewModel,
                    settingsViewModel.uiState.settings.soundEnabled,
                )
                ChineseChessGameScreen(
                    state = gameViewModel.uiState,
                    onSquareTap = gameViewModel::onSquareTap,
                    onUndo = gameViewModel::undo,
                    onHint = gameViewModel::requestHint,
                    onResign = gameViewModel::resign,
                    onDraw = gameViewModel::offerOrAcceptDraw,
                    onRestart = gameViewModel::restart,
                    onBack = navController::popBackStack,
                    onSettings = {
                        navController.navigate(AppDestination.SETTINGS) {
                            launchSingleTop = true
                        }
                    },
                )
            }
            composable(
                route = AppDestination.CHINESE_CHESS_AUTO_PLAY_GAME,
                arguments = listOf(
                    navArgument(AppDestination.AI_DIFFICULTY_ARGUMENT) {
                        type = NavType.IntType
                    },
                ),
            ) { backStackEntry ->
                val difficultyCode = backStackEntry.arguments
                    ?.getInt(AppDestination.AI_DIFFICULTY_ARGUMENT)
                val difficulty = checkNotNull(
                    Difficulty.entries.firstOrNull { it.code == difficultyCode },
                ) {
                    "Unsupported Chinese chess auto-play route"
                }
                val settings = settingsViewModel.uiState.settings
                val factory = remember(
                    gameSessionRepository,
                    gameRecordsViewModel,
                    difficulty,
                    settings.gameDurationMinutes,
                    settings.autoContinueEnabled,
                    settings.autoContinueGameLimit,
                    pikafishNetworkProvider,
                ) {
                    ChineseChessGameViewModel.factory(
                        repository = gameSessionRepository,
                        mode = StoredGameMode.AI_AUTO_PLAY,
                        difficulty = difficulty,
                        onGameRecorded = gameRecordsViewModel::record,
                        timeControlMinutes = settings.gameDurationMinutes,
                        autoContinueEnabled = settings.autoContinueEnabled,
                        autoContinueGameLimit = settings.autoContinueGameLimit,
                        engineFactory = {
                            NativeChineseChessEngine(
                                pikafishNetworkProvider::requireNetworkPath,
                            )
                        },
                    )
                }
                val gameViewModel: ChineseChessGameViewModel = viewModel(factory = factory)
                ChineseChessGameSoundEffect(
                    gameViewModel,
                    settingsViewModel.uiState.settings.soundEnabled,
                )
                ChineseChessGameScreen(
                    state = gameViewModel.uiState,
                    onSquareTap = gameViewModel::onSquareTap,
                    onUndo = gameViewModel::undo,
                    onHint = gameViewModel::requestHint,
                    onResign = gameViewModel::resign,
                    onDraw = gameViewModel::offerOrAcceptDraw,
                    onToggleAutoPlay = gameViewModel::toggleAutoPlayPaused,
                    onAutoPlaySpeedChange = gameViewModel::setAutoPlaySpeed,
                    onAutoPlaySpeedChangeFinished =
                        gameViewModel::persistAutoPlaySpeed,
                    onRestart = gameViewModel::restart,
                    onBack = navController::popBackStack,
                    onSettings = {
                        navController.navigate(AppDestination.SETTINGS) {
                            launchSingleTop = true
                        }
                    },
                )
            }
            composable(AppDestination.GAME_RECORDS) {
                GameRecordsScreen(
                    state = gameRecordsViewModel.uiState,
                    onBack = navController::popBackStack,
                    onCategorySelected = gameRecordsViewModel::selectCategory,
                    onToggleFavorite = gameRecordsViewModel::toggleFavorite,
                    onOpenRecord = { recordId ->
                        navController.navigate(AppDestination.chineseChessRecord(recordId))
                    },
                )
            }
            composable(
                route = AppDestination.CHINESE_CHESS_RECORD,
                arguments = listOf(
                    navArgument(AppDestination.GAME_RECORD_ID_ARGUMENT) {
                        type = NavType.StringType
                    },
                ),
            ) { backStackEntry ->
                val recordId = checkNotNull(
                    backStackEntry.arguments?.getString(AppDestination.GAME_RECORD_ID_ARGUMENT),
                )
                val factory = remember(recordId, gameRecordRepository) {
                    ChineseChessReplayViewModel.factory(
                        recordId = recordId,
                        repository = gameRecordRepository,
                        engineFactory = { NativeChineseChessEngine() },
                    )
                }
                val replayViewModel: ChineseChessReplayViewModel = viewModel(factory = factory)
                ChineseChessReplayScreen(
                    state = replayViewModel.uiState,
                    onBack = navController::popBackStack,
                    onPrevious = replayViewModel::previous,
                    onNext = replayViewModel::next,
                    onJumpToStart = replayViewModel::jumpToStart,
                    onJumpToEnd = replayViewModel::jumpToEnd,
                    onTogglePlayback = replayViewModel::togglePlayback,
                    onSpeedChange = replayViewModel::setSpeed,
                )
            }
            composable(AppDestination.SETTINGS) {
                SettingsScreen(
                    state = settingsViewModel.uiState,
                    onBack = navController::popBackStack,
                    onDefaultDifficulty = settingsViewModel::setDefaultDifficulty,
                    onAutoContinue = settingsViewModel::setAutoContinue,
                    onAdjustAutoContinueLimit =
                        settingsViewModel::adjustAutoContinueLimit,
                    onSoundEnabled = settingsViewModel::setSoundEnabled,
                    onTimeLimitEnabled = settingsViewModel::setTimeLimitEnabled,
                    onAdjustDuration = settingsViewModel::adjustDuration,
                    backupState = backupViewModel.uiState,
                    onExportData = {
                        createBackupDocument.launch(BACKUP_FILE_NAME)
                    },
                    onRestoreData = {
                        openBackupDocument.launch(
                            arrayOf("application/octet-stream", "text/plain"),
                        )
                    },
                    onOpenSourceLicenses = {
                        navController.navigate(AppDestination.OPEN_SOURCE_LICENSES) {
                            launchSingleTop = true
                        }
                    },
                )
                val restoreUri = pendingRestoreUri
                if (restoreUri != null) {
                    AlertDialog(
                        onDismissRequest = { pendingRestoreUri = null },
                        title = {
                            Text(stringResource(R.string.data_backup_restore_confirm_title))
                        },
                        text = {
                            Text(stringResource(R.string.data_backup_restore_confirm_body))
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    pendingRestoreUri = null
                                    navController.navigate(AppDestination.SETTINGS) {
                                        popUpTo(AppDestination.HOME)
                                        launchSingleTop = true
                                    }
                                    backupViewModel.restore(
                                        openInputStream = {
                                            context.contentResolver.openInputStream(restoreUri)
                                        },
                                        onRestored = {
                                            context.findActivity()?.recreate()
                                        },
                                    )
                                },
                            ) {
                                Text(
                                    stringResource(
                                        R.string.data_backup_restore_confirm_action,
                                    ),
                                )
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { pendingRestoreUri = null }) {
                                Text(stringResource(R.string.data_backup_restore_cancel))
                            }
                        },
                    )
                }
            }
            composable(AppDestination.OPEN_SOURCE_LICENSES) {
                OpenSourceLicensesScreen(onBack = navController::popBackStack)
            }
        }
    }
}

private fun isValidChineseChessState(bytes: ByteArray): Boolean =
    try {
        NativeChineseChessEngine().use { engine ->
            engine.restore(bytes) == RestoreResult.Restored
        }
    } catch (_: RuntimeException) {
        false
    } catch (_: LinkageError) {
        false
    }

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

private const val BACKUP_FILE_NAME = "MasterofChessStrategy-backup.mocs"

@Composable
private fun ChineseChessGameSoundEffect(
    viewModel: ChineseChessGameViewModel,
    soundEnabled: Boolean,
) {
    val context = LocalContext.current.applicationContext
    val player = remember(context) { ChineseChessSoundPlayer(context) }
    val currentSoundEnabled = rememberUpdatedState(soundEnabled)
    DisposableEffect(player) {
        onDispose(player::close)
    }
    LaunchedEffect(viewModel, player) {
        viewModel.soundEvents.collect { cue ->
            if (currentSoundEnabled.value) {
                player.play(cue)
            }
        }
    }
}

@Composable
internal fun ChineseChessGameScreen(
    state: ChineseChessGameUiState,
    onSquareTap: (BoardPosition) -> Unit,
    onUndo: () -> Unit,
    onHint: () -> Unit,
    onResign: () -> Unit,
    onRestart: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onDraw: () -> Unit = {},
    onToggleAutoPlay: () -> Unit = {},
    onAutoPlaySpeedChange: (Float) -> Unit = {},
    onAutoPlaySpeedChangeFinished: () -> Unit = {},
    onSettings: () -> Unit = {},
) {
    BackHandler(
        enabled =
            state.isRestoring ||
                state.isPersisting ||
                state.isAiThinking ||
                state.isHintThinking,
    ) {
        // Leave only after engine and persistence work reaches a safe checkpoint.
    }
    MocsTheme {
        Surface(modifier = modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                GameHeader(state, onBack, onSettings)
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
                                onHint = onHint,
                                onResign = onResign,
                                onDraw = onDraw,
                                onToggleAutoPlay = onToggleAutoPlay,
                                onAutoPlaySpeedChange = onAutoPlaySpeedChange,
                                onAutoPlaySpeedChangeFinished =
                                    onAutoPlaySpeedChangeFinished,
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
                                onHint = onHint,
                                onResign = onResign,
                                onDraw = onDraw,
                                onToggleAutoPlay = onToggleAutoPlay,
                                onAutoPlaySpeedChange = onAutoPlaySpeedChange,
                                onAutoPlaySpeedChangeFinished =
                                    onAutoPlaySpeedChangeFinished,
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
                enabled =
                    !state.isRestoring &&
                        !state.isPersisting &&
                        !state.isAiThinking &&
                        !state.isHintThinking,
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
                    text = gameModeTitle(state),
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
                    text = if (state.timeControlMinutes == null) {
                        stringResource(R.string.unlimited_duration)
                    } else {
                        val red = stringResource(
                            R.string.red_clock,
                            formatClock(state.redRemainingMillis),
                        )
                        val black = stringResource(
                            R.string.black_clock,
                            formatClock(state.blackRemainingMillis),
                        )
                        "$red · $black"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(
                onClick = onSettings,
                enabled =
                    !state.isRestoring &&
                        !state.isPersisting &&
                        !state.isAiThinking &&
                        !state.isHintThinking,
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
    onHint: () -> Unit,
    onResign: () -> Unit,
    onDraw: () -> Unit,
    onToggleAutoPlay: () -> Unit,
    onAutoPlaySpeedChange: (Float) -> Unit,
    onAutoPlaySpeedChangeFinished: () -> Unit,
    onRestart: () -> Unit,
    modifier: Modifier,
) {
    val controlAvailable =
        state.isEngineAvailable &&
            !state.isRestoring &&
            !state.isPersisting &&
            !state.isAiThinking &&
            !state.isHintThinking
    var showResignConfirmation by remember { mutableStateOf(false) }
    if (showResignConfirmation) {
        AlertDialog(
            onDismissRequest = { showResignConfirmation = false },
            title = { Text(stringResource(R.string.resign_confirm_title)) },
            text = { Text(stringResource(R.string.resign_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResignConfirmation = false
                        onResign()
                    },
                ) {
                    Text(stringResource(R.string.resign_confirm_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { showResignConfirmation = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
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
            if (state.isEndgame) {
                Text(
                    text = stringResource(
                        R.string.endgame_move_goal,
                        requireNotNull(state.endgameMaxPlayerMoves),
                        state.endgamePlayerMovesUsed,
                    ),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.titleMedium,
                )
            }

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
                    Text(undoButtonText(state))
                }
                OutlinedButton(
                    onClick = onRestart,
                    enabled = controlAvailable,
                    modifier = Modifier
                        .weight(1f)
                        .testTag(RESTART_BUTTON_TAG),
                ) {
                    Text(stringResource(R.string.restart))
                }
            }

            if (state.isAutoPlay) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        onClick = onToggleAutoPlay,
                        enabled =
                            state.isEngineAvailable &&
                                !state.isRestoring &&
                                !state.isPersisting,
                        modifier = Modifier
                            .weight(1f)
                            .testTag(AUTO_PLAY_TOGGLE_TAG),
                    ) {
                        Text(
                            stringResource(
                                if (state.isAutoPlayPaused) {
                                    R.string.resume_auto_play
                                } else {
                                    R.string.pause_auto_play
                                },
                            ),
                        )
                    }
                }
                Text(stringResource(R.string.auto_play_speed, state.autoPlaySpeed))
                Slider(
                    value = state.autoPlaySpeed,
                    onValueChange = onAutoPlaySpeedChange,
                    onValueChangeFinished = onAutoPlaySpeedChangeFinished,
                    valueRange = 0.5f..4f,
                    enabled =
                        state.isEngineAvailable &&
                            !state.isRestoring &&
                            !state.isPersisting,
                    modifier = Modifier.testTag(AUTO_PLAY_SPEED_TAG),
                )
                Text(
                    text = stringResource(
                        R.string.auto_play_progress,
                        state.completedAutoGames,
                        state.autoContinueGameLimit,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        onClick = onHint,
                        enabled =
                            state.isInteractionEnabled &&
                                state.result == GameResult.ONGOING &&
                                state.canRequestHint,
                        modifier = Modifier
                            .weight(1f)
                            .testTag(HINT_BUTTON_TAG),
                    ) {
                        Text(hintButtonText(state))
                    }
                    OutlinedButton(
                        onClick = onDraw,
                        enabled =
                            state.isInteractionEnabled &&
                                state.canOfferOrAcceptDraw,
                        modifier = Modifier
                            .weight(1f)
                            .testTag(DRAW_BUTTON_TAG),
                    ) {
                        Text(
                            stringResource(
                                if (
                                    state.pendingDrawOfferSide != null &&
                                    state.pendingDrawOfferSide != state.currentSide
                                ) {
                                    R.string.accept_draw
                                } else {
                                    R.string.offer_draw
                                },
                            ),
                        )
                    }
                }
                OutlinedButton(
                    onClick = { showResignConfirmation = true },
                    enabled =
                        state.isInteractionEnabled &&
                            state.result == GameResult.ONGOING,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(RESIGN_BUTTON_TAG),
                ) {
                    Text(stringResource(R.string.resign))
                }
            }

            HorizontalDivider()
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
        state.isHintThinking -> stringResource(R.string.calculating_hint)
        state.isPersisting -> stringResource(R.string.saving_game)
        state.result == GameResult.FIRST_PLAYER_WIN -> stringResource(R.string.red_wins)
        state.result == GameResult.SECOND_PLAYER_WIN -> stringResource(R.string.black_wins)
        state.result == GameResult.DRAW -> stringResource(R.string.draw)
        state.currentSide == ChineseChessSide.RED -> stringResource(R.string.red_to_move)
        else -> stringResource(R.string.black_to_move)
    }

@Composable
private fun gameModeTitle(state: ChineseChessGameUiState): String {
    if (state.isEndgame) {
        return stringResource(
            R.string.endgame_game_title,
            requireNotNull(state.endgameTitle),
        )
    }
    val title = when (state.difficulty) {
        Difficulty.EASY -> R.string.difficulty_easy
        Difficulty.MEDIUM -> R.string.difficulty_medium
        Difficulty.HARD -> R.string.difficulty_hard
        Difficulty.MASTER -> R.string.difficulty_master
        null -> return stringResource(R.string.local_two_player)
    }
    return if (state.isAutoPlay) {
        stringResource(R.string.auto_play_game, stringResource(title))
    } else {
        stringResource(
            when (requireNotNull(state.difficulty)) {
                Difficulty.EASY -> R.string.easy_ai_game
                Difficulty.MEDIUM -> R.string.medium_ai_game
                Difficulty.HARD -> R.string.hard_ai_game
                Difficulty.MASTER -> R.string.master_ai_game
            },
        )
    }
}

private fun formatClock(millis: Long?): String {
    if (millis == null) return "--:--"
    val seconds = (millis.coerceAtLeast(0L) + 999L) / 1_000L
    return "%02d:%02d".format(seconds / 60L, seconds % 60L)
}

@Composable
private fun selectionText(state: ChineseChessGameUiState): String =
    if (state.isAutoPlayPaused) {
        stringResource(R.string.auto_play_paused_status)
    } else if (state.isAutoPlay) {
        stringResource(R.string.auto_play_watching)
    } else if (state.isHintThinking) {
        stringResource(R.string.calculating_hint)
    } else if (state.isAiThinking) {
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
            ChineseChessFeedback.UNDO_LIMIT_REACHED -> R.string.feedback_undo_limit
            ChineseChessFeedback.HINT_READY -> R.string.feedback_hint_ready
            ChineseChessFeedback.HINT_LIMIT_REACHED -> R.string.feedback_hint_limit
            ChineseChessFeedback.HINT_UNAVAILABLE -> R.string.feedback_hint_unavailable
            ChineseChessFeedback.PLAYER_RESIGNED -> R.string.feedback_player_resigned
            ChineseChessFeedback.DRAW_OFFERED -> R.string.feedback_draw_offered
            ChineseChessFeedback.DRAW_WAITING -> R.string.feedback_draw_waiting
            ChineseChessFeedback.DRAW_ACCEPTED -> R.string.feedback_draw_accepted
            ChineseChessFeedback.DRAW_DECLINED -> R.string.feedback_draw_declined
            ChineseChessFeedback.TIME_EXPIRED -> R.string.feedback_time_expired
            ChineseChessFeedback.AUTO_PLAY_PAUSED -> R.string.feedback_auto_play_paused
            ChineseChessFeedback.AUTO_PLAY_RESUMED -> R.string.feedback_auto_play_resumed
        },
    )

@Composable
private fun undoButtonText(state: ChineseChessGameUiState): String =
    state.undoRemaining?.let {
        stringResource(R.string.undo_remaining, it)
    } ?: stringResource(R.string.undo_unlimited)

@Composable
private fun hintButtonText(state: ChineseChessGameUiState): String =
    when {
        state.isHintThinking -> stringResource(R.string.calculating_hint)
        !state.canRequestHint && state.hintRemaining == 0 -> {
            stringResource(R.string.hint_not_available)
        }
        state.hintRemaining == null -> stringResource(R.string.hint_unlimited)
        else -> stringResource(R.string.hint_remaining, requireNotNull(state.hintRemaining))
    }

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
        onHint = {},
        onResign = {},
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
