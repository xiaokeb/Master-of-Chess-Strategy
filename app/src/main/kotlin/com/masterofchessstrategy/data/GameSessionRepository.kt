package com.masterofchessstrategy.data

import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.GameType

internal enum class StoredGameMode(val code: Int) {
    LOCAL_TWO_PLAYER(0),
    HUMAN_VS_AI(1),
    AI_AUTO_PLAY(2),
    ENDGAME(3),
    TUTORIAL(4),
}

internal data class GameSessionSnapshot(
    val gameType: GameType,
    val mode: StoredGameMode,
    val difficulty: Difficulty?,
    val engineState: ByteArray,
    val updatedAtEpochMillis: Long,
    val sessionId: String = "",
    val acceptedMoveCount: Int = 0,
    val undoUseCount: Int = 0,
    val hintUseCount: Int = 0,
    val resultOverride: GameResult? = null,
) {
    fun defensiveCopy(): GameSessionSnapshot = copy(engineState = engineState.copyOf())
}

internal sealed interface LoadGameSessionResult {
    data object NotFound : LoadGameSessionResult

    data class Loaded(val snapshot: GameSessionSnapshot) : LoadGameSessionResult

    data object Incompatible : LoadGameSessionResult
}

internal interface GameSessionRepository {
    suspend fun load(gameType: GameType): LoadGameSessionResult

    suspend fun save(snapshot: GameSessionSnapshot)

    suspend fun clear(gameType: GameType)
}

internal class RoomGameSessionRepository(
    private val dao: ActiveGameDao,
) : GameSessionRepository {
    override suspend fun load(gameType: GameType): LoadGameSessionResult {
        val entity = dao.find(gameType.code) ?: return LoadGameSessionResult.NotFound
        if (
            entity.envelopeVersion != CURRENT_ENVELOPE_VERSION ||
            entity.engineFormatVersion != CHINESE_CHESS_ENGINE_FORMAT_VERSION ||
            entity.gameTypeCode != gameType.code ||
            entity.engineState.size !in 1..MAX_ENGINE_STATE_BYTES ||
            entity.acceptedMoveCount !in 0..MAX_TRACKED_ACTIONS ||
            entity.undoUseCount !in 0..MAX_TRACKED_ACTIONS ||
            entity.hintUseCount !in 0..MAX_TRACKED_ACTIONS
        ) {
            return LoadGameSessionResult.Incompatible
        }
        val mode = StoredGameMode.entries.firstOrNull { it.code == entity.modeCode }
            ?: return LoadGameSessionResult.Incompatible
        val difficulty = entity.difficultyCode?.let { code ->
            Difficulty.entries.firstOrNull { it.code == code }
                ?: return LoadGameSessionResult.Incompatible
        }
        val resultOverride = entity.resultOverrideCode?.let { code ->
            decodeTerminalResult(code) ?: return LoadGameSessionResult.Incompatible
        }
        return LoadGameSessionResult.Loaded(
            GameSessionSnapshot(
                gameType = gameType,
                mode = mode,
                difficulty = difficulty,
                engineState = entity.engineState.copyOf(),
                updatedAtEpochMillis = entity.updatedAtEpochMillis,
                sessionId = entity.sessionId,
                acceptedMoveCount = entity.acceptedMoveCount,
                undoUseCount = entity.undoUseCount,
                hintUseCount = entity.hintUseCount,
                resultOverride = resultOverride,
            ),
        )
    }

    override suspend fun save(snapshot: GameSessionSnapshot) {
        require(
            snapshot.sessionId.isNotBlank() &&
                snapshot.sessionId.length <= MatchOutcome.MAX_MATCH_ID_LENGTH
        ) {
            "Session id must contain 1 to 64 characters"
        }
        require(snapshot.engineState.size in 1..MAX_ENGINE_STATE_BYTES) {
            "Engine state size is outside the persistence boundary"
        }
        require(
            snapshot.acceptedMoveCount in 0..MAX_TRACKED_ACTIONS &&
                snapshot.undoUseCount in 0..MAX_TRACKED_ACTIONS &&
                snapshot.hintUseCount in 0..MAX_TRACKED_ACTIONS
        ) {
            "Game action counters are outside the persistence boundary"
        }
        require(snapshot.resultOverride != GameResult.ONGOING) {
            "Only a terminal result may override the engine result"
        }
        dao.upsert(
            ActiveGameEntity(
                gameTypeCode = snapshot.gameType.code,
                modeCode = snapshot.mode.code,
                difficultyCode = snapshot.difficulty?.code,
                envelopeVersion = CURRENT_ENVELOPE_VERSION,
                engineFormatVersion = CHINESE_CHESS_ENGINE_FORMAT_VERSION,
                engineState = snapshot.engineState.copyOf(),
                updatedAtEpochMillis = snapshot.updatedAtEpochMillis,
                sessionId = snapshot.sessionId,
                acceptedMoveCount = snapshot.acceptedMoveCount,
                undoUseCount = snapshot.undoUseCount,
                hintUseCount = snapshot.hintUseCount,
                resultOverrideCode = snapshot.resultOverride?.let(::encodeTerminalResult),
            ),
        )
    }

    override suspend fun clear(gameType: GameType) {
        dao.delete(gameType.code)
    }

    private companion object {
        const val CURRENT_ENVELOPE_VERSION = 2
        const val CHINESE_CHESS_ENGINE_FORMAT_VERSION = 2
        const val MAX_ENGINE_STATE_BYTES = 64 * 1024
        const val MAX_TRACKED_ACTIONS = 10_000

        const val RESULT_FIRST_PLAYER_WIN = 1
        const val RESULT_SECOND_PLAYER_WIN = 2
        const val RESULT_DRAW = 3

        fun encodeTerminalResult(result: GameResult): Int =
            when (result) {
                GameResult.FIRST_PLAYER_WIN -> RESULT_FIRST_PLAYER_WIN
                GameResult.SECOND_PLAYER_WIN -> RESULT_SECOND_PLAYER_WIN
                GameResult.DRAW -> RESULT_DRAW
                GameResult.ONGOING -> error("Ongoing result cannot override engine state")
            }

        fun decodeTerminalResult(code: Int): GameResult? =
            when (code) {
                RESULT_FIRST_PLAYER_WIN -> GameResult.FIRST_PLAYER_WIN
                RESULT_SECOND_PLAYER_WIN -> GameResult.SECOND_PLAYER_WIN
                RESULT_DRAW -> GameResult.DRAW
                else -> null
            }
    }
}
