package com.masterofchessstrategy.data

import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.games.go.data.requireCompletedGoRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    val playerIndex: Int = 0,
    val engineFormatVersion: Int = GameRecordPayloadPolicy.currentVersion(gameType),
) {
    init {
        require(recordId.isNotBlank() && recordId.length <= MAX_RECORD_ID_LENGTH)
        require(result != GameResult.ONGOING)
        require(GameRecordPayloadPolicy.accepts(gameType, engineFormatVersion, engineState)) {
            "Game record format or payload size is invalid"
        }
        require(moveCount in 0..MAX_MOVE_COUNT)
        require(completedAtEpochMillis >= 0L)
        require(isEndgame == (mode == StoredGameMode.ENDGAME))
        require(mode.acceptsPlayerIndex(playerIndex))
    }

    fun defensiveCopy(): GameRecord = copy(engineState = engineState.copyOf())

    companion object {
        const val MAX_RECORD_ID_LENGTH = 64
        // Existing non-Go wire codecs retain this bound; Go has its own native format budget.
        const val MAX_ENGINE_STATE_BYTES = GameRecordPayloadPolicy.XIANGQI_MAX_BYTES
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
    private val admitGoRecord: (GameRecord) -> Unit = ::requireCompletedGoRecord,
) : GameRecordRepository {
    override suspend fun saveCompleted(record: GameRecord): Boolean {
        val owned = record.defensiveCopy()
        admit(owned)
        return dao.insert(owned.toGameRecordEntity()) != INSERT_IGNORED
    }

    override suspend fun list(category: GameRecordCategory): LoadGameRecordsResult {
        val decoded = dao.listAll().map {
            val record = it.toGameRecord() ?: return LoadGameRecordsResult.Incompatible
            if (!canRead(record)) return LoadGameRecordsResult.Incompatible
            record
        }
        val filtered = when (category) {
            GameRecordCategory.ALL -> decoded
            GameRecordCategory.FAVORITES -> decoded.filter(GameRecord::isFavorite)
            GameRecordCategory.ENDGAME -> decoded.filter(GameRecord::isEndgame)
        }
        return LoadGameRecordsResult.Loaded(filtered.map(GameRecord::defensiveCopy))
    }

    override suspend fun load(recordId: String): GameRecord? {
        val record = dao.find(recordId)?.toGameRecord() ?: return null
        return if (canRead(record)) record.defensiveCopy() else null
    }

    private suspend fun admit(record: GameRecord) {
        if (record.gameType == GameType.GO) {
            withContext(Dispatchers.Default) { admitGoRecord(record.defensiveCopy()) }
        }
    }

    private suspend fun canRead(record: GameRecord): Boolean = try {
        admit(record)
        true
    } catch (_: IllegalArgumentException) {
        false
    }

    override suspend fun setFavorite(recordId: String, favorite: Boolean): Boolean {
        require(recordId.isNotBlank() && recordId.length <= GameRecord.MAX_RECORD_ID_LENGTH)
        return dao.setFavorite(recordId, favorite) == 1
    }

    private companion object {
        const val INSERT_IGNORED = -1L
    }
}

internal fun GameRecord.toGameRecordEntity() = GameRecordEntity(
    recordId = recordId,
    gameTypeCode = gameType.code,
    modeCode = mode.code,
    difficultyCode = difficulty?.code,
    resultCode = when (result) {
        GameResult.FIRST_PLAYER_WIN -> 1
        GameResult.SECOND_PLAYER_WIN -> 2
        GameResult.DRAW -> 3
        GameResult.ONGOING -> error("Ongoing games cannot be recorded")
    },
    engineFormatVersion = engineFormatVersion,
    engineState = engineState.copyOf(),
    moveCount = moveCount,
    isFavorite = isFavorite,
    isEndgame = isEndgame,
    completedAtEpochMillis = completedAtEpochMillis,
    playerIndex = playerIndex,
)

internal fun GameRecordEntity.toGameRecord(): GameRecord? {
    val gameType = GameType.entries.firstOrNull { it.code == gameTypeCode } ?: return null
    if (
        !GameRecordPayloadPolicy.accepts(gameType, engineFormatVersion, engineState) ||
        moveCount !in 0..GameRecord.MAX_MOVE_COUNT ||
        completedAtEpochMillis < 0L
    ) {
        return null
    }
    val mode = StoredGameMode.entries.firstOrNull { it.code == modeCode } ?: return null
    val difficulty = difficultyCode?.let { code ->
        Difficulty.entries.firstOrNull { it.code == code } ?: return null
    }
    val decodedResult = when (resultCode) {
        1 -> GameResult.FIRST_PLAYER_WIN
        2 -> GameResult.SECOND_PLAYER_WIN
        3 -> GameResult.DRAW
        else -> return null
    }
    return try {
        GameRecord(
            recordId = recordId,
            gameType = gameType,
            mode = mode,
            difficulty = difficulty,
            result = decodedResult,
            engineState = engineState.copyOf(),
            moveCount = moveCount,
            isFavorite = isFavorite,
            isEndgame = isEndgame,
            completedAtEpochMillis = completedAtEpochMillis,
            playerIndex = playerIndex,
            engineFormatVersion = engineFormatVersion,
        )
    } catch (_: IllegalArgumentException) {
        null
    }
}
