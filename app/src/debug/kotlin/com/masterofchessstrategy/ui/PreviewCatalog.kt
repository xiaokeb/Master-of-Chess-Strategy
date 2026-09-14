package com.masterofchessstrategy.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.masterofchessstrategy.data.AppSettings
import com.masterofchessstrategy.engine.ChineseChessBoard
import com.masterofchessstrategy.engine.ChineseChessPiece
import com.masterofchessstrategy.engine.ChineseChessPieceType
import com.masterofchessstrategy.engine.ChineseChessSide
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.game.ChineseChessGameUiState
import com.masterofchessstrategy.navigation.HomeGameEntry
import com.masterofchessstrategy.settings.AppSettingsUiState
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
