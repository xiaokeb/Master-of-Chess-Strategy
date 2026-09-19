package com.masterofchessstrategy.navigation

import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.challenge.TimedChallengeConfig
import com.masterofchessstrategy.challenge.StreakChallengeState
import com.masterofchessstrategy.challenge.StreakChallengeStateCodec
import com.masterofchessstrategy.custom.CustomPositionStateCodec

internal object AppDestination {
    const val HOME = "home"
    const val CHINESE_CHESS_MODES = "chinese-chess/modes"
    const val CHINESE_CHESS_DIFFICULTY = "chinese-chess/difficulty"
    const val CHINESE_CHESS_AUTO_PLAY_DIFFICULTY =
        "chinese-chess/auto-play/difficulty"
    const val CHINESE_CHESS_TUTORIAL = "chinese-chess/tutorial"
    const val CHINESE_CHESS_ENDGAMES = "chinese-chess/endgames"
    const val CHINESE_CHESS_EXTENSIONS = "chinese-chess/extensions"
    const val CHINESE_CHESS_TIMED_SETUP = "chinese-chess/extensions/timed/setup"
    const val TIMED_DIFFICULTY_ARGUMENT = "timedDifficultyCode"
    const val TIMED_SECONDS_ARGUMENT = "timedSeconds"
    const val CHINESE_CHESS_TIMED_GAME =
        "chinese-chess/extensions/timed/{$TIMED_DIFFICULTY_ARGUMENT}/" +
            "{$TIMED_SECONDS_ARGUMENT}"
    const val CHINESE_CHESS_STREAK_SETUP = "chinese-chess/extensions/streak/setup"
    const val STREAK_DIFFICULTY_ARGUMENT = "streakDifficultyCode"
    const val STREAK_STATE_ARGUMENT = "streakState"
    const val CHINESE_CHESS_STREAK_GAME =
        "chinese-chess/extensions/streak/{$STREAK_DIFFICULTY_ARGUMENT}/" +
            "{$STREAK_STATE_ARGUMENT}"
    const val CHINESE_CHESS_BLIND_SETUP = "chinese-chess/extensions/blind/setup"
    const val BLIND_DIFFICULTY_ARGUMENT = "blindDifficultyCode"
    const val CHINESE_CHESS_BLIND_GAME =
        "chinese-chess/extensions/blind/{$BLIND_DIFFICULTY_ARGUMENT}"
    const val CHINESE_CHESS_CUSTOM_SETUP = "chinese-chess/extensions/custom/setup"
    const val CUSTOM_DIFFICULTY_ARGUMENT = "customDifficultyCode"
    const val CUSTOM_POSITION_ARGUMENT = "customPosition"
    const val CHINESE_CHESS_CUSTOM_GAME =
        "chinese-chess/extensions/custom/{$CUSTOM_DIFFICULTY_ARGUMENT}/" +
            "{$CUSTOM_POSITION_ARGUMENT}"
    const val ENDGAME_LEVEL_ARGUMENT = "levelId"
    const val CHINESE_CHESS_ENDGAME_GAME =
        "chinese-chess/endgames/{$ENDGAME_LEVEL_ARGUMENT}"
    const val CHINESE_CHESS_GAME = "chinese-chess/game"
    const val AI_DIFFICULTY_ARGUMENT = "difficultyCode"
    const val CHINESE_CHESS_AI_GAME =
        "chinese-chess/ai-game/{$AI_DIFFICULTY_ARGUMENT}"
    const val CHINESE_CHESS_AUTO_PLAY_GAME =
        "chinese-chess/auto-play/{$AI_DIFFICULTY_ARGUMENT}"
    const val SETTINGS = "settings"
    const val OPEN_SOURCE_LICENSES = "settings/open-source-licenses"
    const val GAME_RECORDS = "records"
    const val GAME_RECORD_ID_ARGUMENT = "recordId"
    const val CHINESE_CHESS_RECORD = "records/{$GAME_RECORD_ID_ARGUMENT}"

    fun chineseChessAiGame(difficulty: Difficulty): String =
        "chinese-chess/ai-game/" + difficulty.code

    fun chineseChessAutoPlayGame(difficulty: Difficulty): String =
        "chinese-chess/auto-play/" + difficulty.code

    fun chineseChessRecord(recordId: String): String {
        require(recordId.isNotBlank() && '/' !in recordId)
        return "records/$recordId"
    }

    fun chineseChessEndgame(levelId: String): String {
        require(levelId.isNotBlank() && '/' !in levelId)
        return "chinese-chess/endgames/$levelId"
    }

    fun chineseChessCustomGame(difficulty: Difficulty, position: ByteArray): String =
        "chinese-chess/extensions/custom/${difficulty.code}/" +
            CustomPositionStateCodec.encode(position)

    fun chineseChessTimedGame(difficulty: Difficulty, secondsPerMove: Int): String {
        TimedChallengeConfig.sessionVariant(secondsPerMove)
        return "chinese-chess/extensions/timed/${difficulty.code}/$secondsPerMove"
    }

    fun chineseChessStreakGame(
        difficulty: Difficulty,
        state: StreakChallengeState,
    ): String =
        "chinese-chess/extensions/streak/${difficulty.code}/" +
            StreakChallengeStateCodec.encode(state)

    fun chineseChessBlindGame(difficulty: Difficulty): String =
        "chinese-chess/extensions/blind/${difficulty.code}"
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
    AI_AUTO_PLAY(ModeDestination.AUTO_PLAY_DIFFICULTY),
    ENDGAME(ModeDestination.ENDGAME_CATALOG),
    TUTORIAL(ModeDestination.TUTORIAL),
    EXTENSIONS(ModeDestination.EXTENSIONS),
}

internal enum class ModeDestination {
    GAME,
    DIFFICULTY,
    AUTO_PLAY_DIFFICULTY,
    TUTORIAL,
    ENDGAME_CATALOG,
    EXTENSIONS,
    LOCKED,
}

/** Difficulty remains locked until its documented prerequisite is implemented. */
internal data class DifficultyEntry(
    val difficulty: Difficulty,
    val isUnlocked: Boolean,
    val isPlayable: Boolean,
)

internal fun chineseChessDifficulties(
    tutorialCompleted: Boolean,
    winsByDifficulty: Map<Difficulty, Int> = emptyMap(),
): List<DifficultyEntry> =
    Difficulty.entries.map { difficulty ->
        val isUnlocked = tutorialCompleted && when (difficulty) {
            Difficulty.EASY -> true
            Difficulty.MEDIUM -> (winsByDifficulty[Difficulty.EASY] ?: 0) >= 10
            Difficulty.HARD -> (winsByDifficulty[Difficulty.MEDIUM] ?: 0) >= 15
            Difficulty.MASTER -> (winsByDifficulty[Difficulty.HARD] ?: 0) >= 20
        }
        DifficultyEntry(
            difficulty = difficulty,
            isUnlocked = isUnlocked,
            isPlayable = isUnlocked,
        )
    }
