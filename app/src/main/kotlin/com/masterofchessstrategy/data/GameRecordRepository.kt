package com.masterofchessstrategy.data

import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.GameType

internal enum class GameRecordCategory {
    ALL,
    FAVORITES,
    ENDGAME,
}

internal data class GameRecord(
    val recordId: String,
    val gameType: GameType,
    val mode: StoredGameMode,
    val difficulty: Difficulty?,
    val result: GameResult,
    val engineState: ByteArray,
    val moveCount: Int,
    val isFavorite: Boolean = false,
    val isEndgame: Boolean = false,
    val completedAtEpochMillis: Long,
) {
    init {
        require(recordId.isNotBlank() && recordId.length <= MAX_RECORD_ID_LENGTH)
        require(result != GameResult.ONGOING)
        require(engineState.size in 1..MAX_ENGINE_STATE_BYTES)
        require(moveCount in 0..MAX_MOVE_COUNT)
        require(completedAtEpochMillis >= 0L)
        require(isEndgame == (mode == StoredGameMode.ENDGAME))
    }

    fun defensiveCopy(): GameRecord = copy(engineState = engineState.copyOf())

    companion object {
        const val MAX_RECORD_ID_LENGTH = 64
        const val MAX_ENGINE_STATE_BYTES = 64 * 1024
        const val MAX_MOVE_COUNT = 10_000
    }
}

internal sealed interface LoadGameRecordsResult {
    data class Loaded(val records: List<GameRecord>) : LoadGameRecordsResult

    data object Incompatible : LoadGameRecordsResult
}

internal interface GameRecordRepository {
    suspend fun saveCompleted(record: GameRecord): Boolean

    suspend fun list(category: GameRecordCategory): LoadGameRecordsResult

    suspend fun load(recordId: String): GameRecord?

    suspend fun setFavorite(recordId: String, favorite: Boolean): Boolean
}

internal class RoomGameRecordRepository(
    private val dao: GameRecordDao,
) : GameRecordRepository {
    override suspend fun saveCompleted(record: GameRecord): Boolean =
        dao.insert(record.toEntity()) != INSERT_IGNORED

    override suspend fun list(category: GameRecordCategory): LoadGameRecordsResult {
        val decoded = dao.listAll().map { it.toRecord() ?: return LoadGameRecordsResult.Incompatible }
        val filtered = when (category) {
            GameRecordCategory.ALL -> decoded
            GameRecordCategory.FAVORITES -> decoded.filter(GameRecord::isFavorite)
            GameRecordCategory.ENDGAME -> decoded.filter(GameRecord::isEndgame)
        }
        return LoadGameRecordsResult.Loaded(filtered.map(GameRecord::defensiveCopy))
    }

    override suspend fun load(recordId: String): GameRecord? =
        dao.find(recordId)?.toRecord()?.defensiveCopy()

    override suspend fun setFavorite(recordId: String, favorite: Boolean): Boolean {
        require(recordId.isNotBlank() && recordId.length <= GameRecord.MAX_RECORD_ID_LENGTH)
        return dao.setFavorite(recordId, favorite) == 1
    }

    private fun GameRecord.toEntity() =
        GameRecordEntity(
            recordId = recordId,
            gameTypeCode = gameType.code,
            modeCode = mode.code,
            difficultyCode = difficulty?.code,
            resultCode = result.encode(),
            engineFormatVersion = CHINESE_CHESS_ENGINE_FORMAT_VERSION,
            engineState = engineState.copyOf(),
            moveCount = moveCount,
            isFavorite = isFavorite,
            isEndgame = isEndgame,
            completedAtEpochMillis = completedAtEpochMillis,
        )

    private fun GameRecordEntity.toRecord(): GameRecord? {
        if (
            engineFormatVersion != CHINESE_CHESS_ENGINE_FORMAT_VERSION ||
            engineState.size !in 1..GameRecord.MAX_ENGINE_STATE_BYTES ||
            moveCount !in 0..GameRecord.MAX_MOVE_COUNT ||
            completedAtEpochMillis < 0L
        ) {
            return null
        }
        val gameType = GameType.entries.firstOrNull { it.code == gameTypeCode } ?: return null
        val mode = StoredGameMode.entries.firstOrNull { it.code == modeCode } ?: return null
        val difficulty = difficultyCode?.let { code ->
            Difficulty.entries.firstOrNull { it.code == code } ?: return null
        }
        val result = resultCode.decode() ?: return null
        return try {
            GameRecord(
                recordId = recordId,
                gameType = gameType,
                mode = mode,
                difficulty = difficulty,
                result = result,
                engineState = engineState.copyOf(),
                moveCount = moveCount,
                isFavorite = isFavorite,
                isEndgame = isEndgame,
                completedAtEpochMillis = completedAtEpochMillis,
            )
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun GameResult.encode(): Int =
        when (this) {
            GameResult.FIRST_PLAYER_WIN -> RESULT_FIRST_PLAYER_WIN
            GameResult.SECOND_PLAYER_WIN -> RESULT_SECOND_PLAYER_WIN
            GameResult.DRAW -> RESULT_DRAW
            GameResult.ONGOING -> error("Ongoing games cannot be recorded")
        }

    private fun Int.decode(): GameResult? =
        when (this) {
            RESULT_FIRST_PLAYER_WIN -> GameResult.FIRST_PLAYER_WIN
            RESULT_SECOND_PLAYER_WIN -> GameResult.SECOND_PLAYER_WIN
            RESULT_DRAW -> GameResult.DRAW
            else -> null
        }

    private companion object {
        const val INSERT_IGNORED = -1L
        const val CHINESE_CHESS_ENGINE_FORMAT_VERSION = 2
        const val RESULT_FIRST_PLAYER_WIN = 1
        const val RESULT_SECOND_PLAYER_WIN = 2
        const val RESULT_DRAW = 3
    }
}
