package com.masterofchessstrategy.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.masterofchessstrategy.data.AppSettings
import com.masterofchessstrategy.data.GameRecord
import com.masterofchessstrategy.data.GameRecordCategory
import com.masterofchessstrategy.data.CompletedEndgameLevel
import com.masterofchessstrategy.data.EndgameProgress
import com.masterofchessstrategy.data.StoredGameMode
import com.masterofchessstrategy.challenge.ChineseChessTimedChallengeUiState
import com.masterofchessstrategy.challenge.ChineseChessStreakUiState
import com.masterofchessstrategy.challenge.PreparedTimedChallenge
import com.masterofchessstrategy.challenge.PreparedStreakChallenge
import com.masterofchessstrategy.challenge.StreakChallengeState
import com.masterofchessstrategy.custom.ChineseChessSetupUiState
import com.masterofchessstrategy.engine.ChineseChessBoard
import com.masterofchessstrategy.engine.BoardMove
import com.masterofchessstrategy.engine.BoardPosition
import com.masterofchessstrategy.engine.ChineseChessPiece
import com.masterofchessstrategy.engine.ChineseChessPieceType
import com.masterofchessstrategy.engine.ChineseChessSide
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.PositionedChineseChessPiece
import com.masterofchessstrategy.endgame.ChineseChessEndgameLevel
import com.masterofchessstrategy.endgame.ChineseChessEndgameUiState
import com.masterofchessstrategy.endgame.EndgameLevelEntry
import com.masterofchessstrategy.game.ChineseChessGameUiState
import com.masterofchessstrategy.navigation.HomeGameEntry
import com.masterofchessstrategy.records.ChineseChessReplayFrame
import com.masterofchessstrategy.records.ChineseChessReplayUiState
import com.masterofchessstrategy.records.GameRecordsUiState
import com.masterofchessstrategy.settings.AppSettingsUiState
import com.masterofchessstrategy.settings.LocalDataBackupUiState
import com.masterofchessstrategy.ui.theme.MocsTheme

/**
 * Android Studio design catalog for completed screens.
 *
 * These previews live in the debug source set, so demonstration fixtures never
 * enter the release APK.
 */
@Preview(
    name = "01 首页",
    group = "已完成界面",
    widthDp = 960,
    heightDp = 540,
    showBackground = true,
)
@Composable
private fun HomeScreenPreview() {
    MocsTheme {
        HomeScreen(
            onGameSelected = {},
            quickStartEntries = setOf(HomeGameEntry.CHINESE_CHESS),
            onQuickStart = {},
            onSettings = {},
            onRecords = {},
            playerSummary = LocalPlayerSummary(
                rank = "棋士",
                wins = 27,
                stars = 12,
                score = 1_680,
            ),
        )
    }
}

@Preview(
    name = "02 中国象棋模式",
    group = "已完成界面",
    widthDp = 960,
    heightDp = 540,
    showBackground = true,
)
@Composable
private fun ChineseChessModeScreenPreview() {
    MocsTheme {
        ChineseChessModeScreen(
            onBack = {},
            onLocalGame = {},
            onAiDifficulty = {},
            onAutoPlayDifficulty = {},
            onTutorial = {},
            onEndgame = {},
            onExtensions = {},
        )
    }
}

@Preview(
    name = "03 自动演局",
    group = "已完成界面",
    widthDp = 960,
    heightDp = 540,
    showBackground = true,
)
@Composable
private fun AutoPlayGameScreenPreview() {
    ChineseChessGameScreen(
        state = ChineseChessGameUiState(
            board = standardChineseChessBoard(),
            currentSide = ChineseChessSide.BLACK,
            isAiGame = true,
            isAutoPlay = true,
            isAutoPlayPaused = true,
            autoPlaySpeed = 2f,
            completedAutoGames = 3,
            autoContinueGameLimit = 10,
            difficulty = Difficulty.HARD,
            timeControlMinutes = 30,
            redRemainingMillis = 1_425_000L,
            blackRemainingMillis = 1_382_000L,
        ),
        onSquareTap = {},
        onUndo = {},
        onHint = {},
        onResign = {},
        onDraw = {},
        onToggleAutoPlay = {},
        onAutoPlaySpeedChange = {},
        onRestart = {},
        onBack = {},
        onSettings = {},
    )
}

@Preview(
    name = "04 设置",
    group = "已完成界面",
    widthDp = 960,
    heightDp = 540,
    showBackground = true,
)
@Composable
private fun SettingsScreenPreview() {
    MocsTheme {
        SettingsScreen(
            state = AppSettingsUiState(
                settings = AppSettings(
                    defaultDifficulty = Difficulty.HARD,
                    autoContinueEnabled = true,
                    autoContinueGameLimit = 10,
                    soundEnabled = true,
                    gameDurationMinutes = 30,
                    updatedAtEpochMillis = 0L,
                ),
                isLoading = false,
            ),
            onBack = {},
            onDefaultDifficulty = {},
            onAutoContinue = {},
            onAdjustAutoContinueLimit = {},
            onSoundEnabled = {},
            onTimeLimitEnabled = {},
            onAdjustDuration = {},
            backupState = LocalDataBackupUiState(),
            onExportData = {},
            onRestoreData = {},
            onOpenSourceLicenses = {},
        )
    }
}

@Preview(
    name = "05 开源许可",
    group = "已完成界面",
    widthDp = 960,
    heightDp = 540,
    showBackground = true,
)
@Composable
private fun OpenSourceLicensesScreenPreview() {
    MocsTheme {
        OpenSourceLicensesContent(
            documents = LegalDocuments(
                notices = "Master of Chess Strategy 采用 GPL-3.0-or-later。\n\n" +
                    "Pikafish 引擎采用 GPL-3.0-or-later。",
                gpl = "GNU GENERAL PUBLIC LICENSE\nVersion 3, 29 June 2007",
                network = "Pikafish NNUE 权重仅限合法用途；未经许可不得商用。",
            ),
            onBack = {},
        )
    }
}

@Preview(
    name = "06 棋谱管理",
    group = "已完成界面",
    widthDp = 960,
    heightDp = 540,
    showBackground = true,
)
@Composable
private fun GameRecordsScreenPreview() {
    MocsTheme {
        GameRecordsScreen(
            state = GameRecordsUiState(
                category = GameRecordCategory.ALL,
                records = listOf(previewRecord()),
                isLoading = false,
            ),
            onBack = {},
            onCategorySelected = {},
            onToggleFavorite = {},
            onOpenRecord = {},
        )
    }
}

@Preview(
    name = "07 棋谱回放",
    group = "已完成界面",
    widthDp = 960,
    heightDp = 540,
    showBackground = true,
)
@Composable
private fun ChineseChessReplayScreenPreview() {
    MocsTheme {
        ChineseChessReplayScreen(
            state = ChineseChessReplayUiState(
                record = previewRecord(),
                frames = listOf(
                    ChineseChessReplayFrame(
                        board = standardChineseChessBoard(),
                        sideToMove = ChineseChessSide.RED,
                    ),
                ),
                isLoading = false,
            ),
            onBack = {},
            onPrevious = {},
            onNext = {},
            onJumpToStart = {},
            onJumpToEnd = {},
            onTogglePlayback = {},
            onSpeedChange = {},
        )
    }
}

@Preview(
    name = "08 中国象棋残局",
    group = "已完成界面",
    widthDp = 960,
    heightDp = 540,
    showBackground = true,
)
@Composable
private fun ChineseChessEndgameScreenPreview() {
    val first = previewEndgameLevel("xq-easy-001", 1, "卧槽马锁宫")
    val second = previewEndgameLevel("xq-easy-002", 2, "双马架炮")
    val completion = CompletedEndgameLevel(
        levelId = first.id,
        difficulty = Difficulty.EASY,
        bestPlayerMoves = 1,
        starsAwarded = 1,
        scoreAwarded = 10,
        completedAtEpochMillis = 1L,
    )
    val entries = listOf(
        EndgameLevelEntry(first, true, true, completion),
        EndgameLevelEntry(second, true, true, null),
    )
    MocsTheme {
        ChineseChessEndgameScreen(
            state = ChineseChessEndgameUiState(
                entries = entries,
                visibleEntries = entries,
                themes = listOf("一步杀"),
                progress = EndgameProgress(mapOf(first.id to completion)),
                isLoading = false,
            ),
            onBack = {},
            onModeSelected = {},
            onDifficultySelected = {},
            onThemeSelected = {},
            onRandomChallenge = {},
            onLevelSelected = {},
        )
    }
}

@Preview(
    name = "09 中国象棋自由摆局",
    group = "已完成界面",
    widthDp = 960,
    heightDp = 540,
    showBackground = true,
)
@Composable
private fun ChineseChessSetupScreenPreview() {
    MocsTheme {
        ChineseChessSetupScreen(
            state = ChineseChessSetupUiState(
                board = standardChineseChessBoard(),
                unlockedDifficulties = Difficulty.entries.toSet(),
                isLoadingSavedGame = false,
                savedGameAvailable = true,
            ),
            onBack = {},
            onSquareTap = {},
            onSideSelected = {},
            onPieceSelected = {},
            onSideToMoveSelected = {},
            onDifficultySelected = {},
            onClear = {},
            onResetStandard = {},
            onStart = {},
            onContinueSaved = {},
        )
    }
}

@Preview(
    name = "10 中国象棋限时挑战",
    group = "已完成界面",
    widthDp = 960,
    heightDp = 540,
    showBackground = true,
)
@Composable
private fun ChineseChessTimedChallengeScreenPreview() {
    MocsTheme {
        ChineseChessTimedChallengeScreen(
            state = ChineseChessTimedChallengeUiState(
                difficulty = Difficulty.MEDIUM,
                secondsPerMove = 30,
                unlockedDifficulties = Difficulty.entries.toSet(),
                isLoadingSavedGame = false,
                savedChallenge = PreparedTimedChallenge(
                    difficulty = Difficulty.EASY,
                    secondsPerMove = 10,
                ),
            ),
            onBack = {},
            onDifficultySelected = {},
            onSecondsSelected = {},
            onStart = {},
            onContinueSaved = {},
        )
    }
}

@Preview(
    name = "11 中国象棋连胜模式",
    group = "已完成界面",
    widthDp = 960,
    heightDp = 540,
    showBackground = true,
)
@Composable
private fun ChineseChessStreakChallengeScreenPreview() {
    MocsTheme {
        ChineseChessStreakChallengeScreen(
            state = ChineseChessStreakUiState(
                startingDifficulty = Difficulty.MEDIUM,
                unlockedDifficulties = Difficulty.entries.toSet(),
                isLoadingSavedGame = false,
                savedChallenge = PreparedStreakChallenge(
                    difficulty = Difficulty.HARD,
                    state = StreakChallengeState(
                        currentStreak = 4,
                        bestStreak = 7,
                        winsAtDifficulty = 1,
                    ),
                ),
            ),
            onBack = {},
            onDifficultySelected = {},
            onStart = {},
            onContinueSaved = {},
        )
    }
}

private fun previewRecord() = GameRecord(
    recordId = "preview-record",
    gameType = com.masterofchessstrategy.engine.GameType.CHINESE_CHESS,
    mode = StoredGameMode.HUMAN_VS_AI,
    difficulty = Difficulty.HARD,
    result = GameResult.FIRST_PLAYER_WIN,
    engineState = byteArrayOf(1),
    moveCount = 42,
    isFavorite = true,
    completedAtEpochMillis = 1_789_315_200_000L,
)

private fun previewEndgameLevel(
    id: String,
    order: Int,
    title: String,
) = ChineseChessEndgameLevel(
    id = id,
    difficulty = Difficulty.EASY,
    chapterOrder = order,
    title = title,
    theme = "一步杀",
    sideToMove = ChineseChessSide.RED,
    maxPlayerMoves = 1,
    starReward = 1,
    scoreReward = 10,
    pieces = listOf(
        PositionedChineseChessPiece(
            BoardPosition(4, 9),
            ChineseChessPiece(ChineseChessPieceType.GENERAL, ChineseChessSide.RED),
        ),
        PositionedChineseChessPiece(
            BoardPosition(4, 0),
            ChineseChessPiece(ChineseChessPieceType.GENERAL, ChineseChessSide.BLACK),
        ),
    ),
    principalVariation = listOf(BoardMove(BoardPosition(4, 9), BoardPosition(4, 8))),
)

private fun standardChineseChessBoard(): List<ChineseChessPiece?> {
    val board = MutableList<ChineseChessPiece?>(
        ChineseChessBoard.WIDTH * ChineseChessBoard.HEIGHT,
    ) { null }
    val backRank = listOf(
        ChineseChessPieceType.CHARIOT,
        ChineseChessPieceType.HORSE,
        ChineseChessPieceType.ELEPHANT,
        ChineseChessPieceType.ADVISOR,
        ChineseChessPieceType.GENERAL,
        ChineseChessPieceType.ADVISOR,
        ChineseChessPieceType.ELEPHANT,
        ChineseChessPieceType.HORSE,
        ChineseChessPieceType.CHARIOT,
    )
    fun place(x: Int, y: Int, type: ChineseChessPieceType, side: ChineseChessSide) {
        board[y * ChineseChessBoard.WIDTH + x] = ChineseChessPiece(type, side)
    }
    backRank.forEachIndexed { x, type ->
        place(x, 0, type, ChineseChessSide.BLACK)
        place(x, 9, type, ChineseChessSide.RED)
    }
    listOf(1, 7).forEach { x ->
        place(x, 2, ChineseChessPieceType.CANNON, ChineseChessSide.BLACK)
        place(x, 7, ChineseChessPieceType.CANNON, ChineseChessSide.RED)
    }
    listOf(0, 2, 4, 6, 8).forEach { x ->
        place(x, 3, ChineseChessPieceType.SOLDIER, ChineseChessSide.BLACK)
        place(x, 6, ChineseChessPieceType.SOLDIER, ChineseChessSide.RED)
    }
    return board
}
