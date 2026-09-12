package com.masterofchessstrategy.data

import com.masterofchessstrategy.engine.Difficulty
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
            entity.engineState.size !in 1..MAX_ENGINE_STATE_BYTES
        ) {
            return LoadGameSessionResult.Incompatible
        }
        val mode = StoredGameMode.entries.firstOrNull { it.code == entity.modeCode }
            ?: return LoadGameSessionResult.Incompatible
        val difficulty = entity.difficultyCode?.let { code ->
            Difficulty.entries.firstOrNull { it.code == code }
                ?: return LoadGameSessionResult.Incompatible
        }
        return LoadGameSessionResult.Loaded(
            GameSessionSnapshot(
                gameType = gameType,
                mode = mode,
                difficulty = difficulty,
                engineState = entity.engineState.copyOf(),
                updatedAtEpochMillis = entity.updatedAtEpochMillis,
            ),
        )
    }

    override suspend fun save(snapshot: GameSessionSnapshot) {
        require(snapshot.engineState.size in 1..MAX_ENGINE_STATE_BYTES) {
            "Engine state size is outside the persistence boundary"
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
            ),
        )
    }

    override suspend fun clear(gameType: GameType) {
        dao.delete(gameType.code)
    }

    private companion object {
        const val CURRENT_ENVELOPE_VERSION = 1
        const val CHINESE_CHESS_ENGINE_FORMAT_VERSION = 2
        const val MAX_ENGINE_STATE_BYTES = 64 * 1024
    }
}
