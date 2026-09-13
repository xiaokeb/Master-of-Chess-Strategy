package com.masterofchessstrategy.navigation

import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.GameType

internal object AppDestination {
    const val HOME = "home"
    const val CHINESE_CHESS_MODES = "chinese-chess/modes"
    const val CHINESE_CHESS_DIFFICULTY = "chinese-chess/difficulty"
    const val CHINESE_CHESS_TUTORIAL = "chinese-chess/tutorial"
    const val CHINESE_CHESS_GAME = "chinese-chess/game"
    const val SETTINGS = "settings"
}

/** Five first-level cards required by the product layout. */
internal enum class HomeGameEntry(
    val gameTypes: Set<GameType>,
    val isAvailable: Boolean,
) {
    CHINESE_CHESS(setOf(GameType.CHINESE_CHESS), true),
    GO(setOf(GameType.GO), false),
    STANDARD_MAHJONG(setOf(GameType.STANDARD_MAHJONG), false),
    LANZHOU_MAHJONG(setOf(GameType.LANZHOU_MAHJONG), false),
    LANZHOU_TRADITIONAL(
        setOf(GameType.LANZHOU_SQUARE_CHESS, GameType.TIGER_AND_GOAT),
        false,
    ),
}

internal enum class ChineseChessMode(
    val destination: ModeDestination,
) {
    LOCAL_TWO_PLAYER(ModeDestination.GAME),
    HUMAN_VS_AI(ModeDestination.DIFFICULTY),
    AI_AUTO_PLAY(ModeDestination.LOCKED),
    ENDGAME(ModeDestination.LOCKED),
    TUTORIAL(ModeDestination.TUTORIAL),
}

internal enum class ModeDestination {
    GAME,
    DIFFICULTY,
    TUTORIAL,
    LOCKED,
}

/** Difficulty remains locked until its documented prerequisite is implemented. */
internal data class DifficultyEntry(
    val difficulty: Difficulty,
    val isUnlocked: Boolean,
)

internal fun chineseChessDifficulties(tutorialCompleted: Boolean): List<DifficultyEntry> =
    Difficulty.entries.map { difficulty ->
        DifficultyEntry(
            difficulty = difficulty,
            isUnlocked = difficulty == Difficulty.EASY && tutorialCompleted,
        )
    }
