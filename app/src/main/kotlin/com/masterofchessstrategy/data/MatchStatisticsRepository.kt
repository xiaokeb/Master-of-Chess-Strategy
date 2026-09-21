package com.masterofchessstrategy.data

import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.GameType

internal data class MatchOutcome(
    val matchId: String,
    val gameType: GameType,
    val mode: StoredGameMode,
    val difficulty: Difficulty,
    val playerIndex: Int,
    val result: GameResult,
    val settledAtEpochMillis: Long,
) {
    init {
        require(matchId.isNotBlank() && matchId.length <= MAX_MATCH_ID_LENGTH) {
            "Match id must contain 1 to 64 characters"
        }
        require(mode == StoredGameMode.HUMAN_VS_AI) {
            "Only completed player versus AI matches are ranked"
        }
        require(playerIndex in 0..1) {
            "Player index must identify the first or second player"
        }
        require(result != GameResult.ONGOING) {
            "An ongoing match cannot be settled"
        }
        require(settledAtEpochMillis >= 0L) {
            "Settlement time cannot be negative"
        }
    }

    val isWin: Boolean
        get() =
            (playerIndex == 0 && result == GameResult.FIRST_PLAYER_WIN) ||
                (playerIndex == 1 && result == GameResult.SECOND_PLAYER_WIN)

    val isLoss: Boolean
        get() =
            (playerIndex == 0 && result == GameResult.SECOND_PLAYER_WIN) ||
                (playerIndex == 1 && result == GameResult.FIRST_PLAYER_WIN)

    companion object {
        const val MAX_MATCH_ID_LENGTH = 64
    }
}

internal data class PlayerStatistics(
    val completedMatches: Int = 0,
    val totalWins: Int = 0,
    val totalLosses: Int = 0,
    val totalDraws: Int = 0,
    val netStars: Int = 0,
    val stars: Int = 0,
    val score: Int = 0,
    val byGame: Map<GameType, GameStatistics> = emptyMap(),
    val winsByGameAndDifficulty: Map<GameType, Map<Difficulty, Int>> = emptyMap(),
) {
    fun winsAt(gameType: GameType, difficulty: Difficulty): Int =
        winsByGameAndDifficulty[gameType]?.get(difficulty) ?: 0

    companion object {
        val EMPTY = PlayerStatistics()
    }
}

internal data class GameStatistics(
    val completedMatches: Int = 0,
    val wins: Int = 0,
    val losses: Int = 0,
    val draws: Int = 0,
)

internal sealed interface LoadMatchStatisticsResult {
    data class Loaded(val statistics: PlayerStatistics) : LoadMatchStatisticsResult

    data object Incompatible : LoadMatchStatisticsResult
}

internal data class RecordMatchOutcomeResult(
    val wasRecorded: Boolean,
    val statistics: PlayerStatistics,
)

internal interface MatchStatisticsRepository {
    suspend fun load(): LoadMatchStatisticsResult

    suspend fun record(outcome: MatchOutcome): RecordMatchOutcomeResult
}

internal class RoomMatchStatisticsRepository(
    private val dao: MatchOutcomeDao,
) : MatchStatisticsRepository {
    override suspend fun load(): LoadMatchStatisticsResult =
        decodeStatistics(dao.listAll())?.let(LoadMatchStatisticsResult::Loaded)
            ?: LoadMatchStatisticsResult.Incompatible

    override suspend fun record(outcome: MatchOutcome): RecordMatchOutcomeResult {
        val inserted = dao.insert(outcome.toMatchOutcomeEntity()) != INSERT_IGNORED
        val statistics = checkNotNull(decodeStatistics(dao.listAll())) {
            "Match outcome ledger contains incompatible data"
        }
        return RecordMatchOutcomeResult(
            wasRecorded = inserted,
            statistics = statistics,
        )
    }

    private fun decodeStatistics(
        entities: List<MatchOutcomeEntity>,
    ): PlayerStatistics? {
        val outcomes = entities.map { it.toMatchOutcome() ?: return null }

        val wins = outcomes.count(MatchOutcome::isWin)
        val losses = outcomes.count(MatchOutcome::isLoss)
        val draws = outcomes.size - wins - losses
        val winsByGameAndDifficulty = GameType.entries.associateWith { gameType ->
            Difficulty.entries.associateWith { difficulty ->
                outcomes.count {
                    it.isWin &&
                        it.gameType == gameType &&
                        it.difficulty == difficulty
                }
            }
        }
        val byGame = GameType.entries.associateWith { gameType ->
            val gameOutcomes = outcomes.filter { it.gameType == gameType }
            val gameWins = gameOutcomes.count(MatchOutcome::isWin)
            val gameLosses = gameOutcomes.count(MatchOutcome::isLoss)
            GameStatistics(
                completedMatches = gameOutcomes.size,
                wins = gameWins,
                losses = gameLosses,
                draws = gameOutcomes.size - gameWins - gameLosses,
            )
        }
        val score = outcomes.sumOf { outcome ->
            val base = DIFFICULTY_COEFFICIENTS.getValue(outcome.difficulty) *
                BASE_SCORE
            when {
                outcome.isWin -> base
                outcome.isLoss -> -base * LOSS_MULTIPLIER
                else -> 0
            }
        }
        val netStars = (wins - losses).coerceAtLeast(0)
        return PlayerStatistics(
            completedMatches = outcomes.size,
            totalWins = wins,
            totalLosses = losses,
            totalDraws = draws,
            netStars = netStars,
            stars = netStars % STARS_PER_RANK,
            score = score,
            byGame = byGame,
            winsByGameAndDifficulty = winsByGameAndDifficulty,
        )
    }

    private companion object {
        const val INSERT_IGNORED = -1L
        const val BASE_SCORE = 10
        const val LOSS_MULTIPLIER = 2
        const val STARS_PER_RANK = 5
        val DIFFICULTY_COEFFICIENTS = mapOf(
            Difficulty.EASY to 1,
            Difficulty.MEDIUM to 2,
            Difficulty.HARD to 4,
            Difficulty.MASTER to 8,
        )
    }
}

internal fun MatchOutcome.toMatchOutcomeEntity() = MatchOutcomeEntity(
    matchId = matchId,
    gameTypeCode = gameType.code,
    modeCode = mode.code,
    difficultyCode = difficulty.code,
    playerIndex = playerIndex,
    resultCode = when (result) {
        GameResult.FIRST_PLAYER_WIN -> 1
        GameResult.SECOND_PLAYER_WIN -> 2
        GameResult.DRAW -> 3
        GameResult.ONGOING -> error("Ongoing matches cannot be persisted")
    },
    settledAtEpochMillis = settledAtEpochMillis,
)

internal fun MatchOutcomeEntity.toMatchOutcome(): MatchOutcome? {
    val gameType = GameType.entries.firstOrNull { it.code == gameTypeCode } ?: return null
    val mode = StoredGameMode.entries.firstOrNull { it.code == modeCode } ?: return null
    val difficulty = Difficulty.entries.firstOrNull { it.code == difficultyCode } ?: return null
    val result = when (resultCode) {
        1 -> GameResult.FIRST_PLAYER_WIN
        2 -> GameResult.SECOND_PLAYER_WIN
        3 -> GameResult.DRAW
        else -> return null
    }
    return try {
        MatchOutcome(
            matchId = matchId,
            gameType = gameType,
            mode = mode,
            difficulty = difficulty,
            playerIndex = playerIndex,
            result = result,
            settledAtEpochMillis = settledAtEpochMillis,
        )
    } catch (_: IllegalArgumentException) {
        null
    }
}
